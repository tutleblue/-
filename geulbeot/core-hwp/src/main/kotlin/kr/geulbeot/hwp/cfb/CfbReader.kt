package kr.geulbeot.hwp.cfb

import kr.geulbeot.hwp.util.ByteReader
import kr.geulbeot.hwp.util.HwpFormatException

/**
 * Reads a Compound File Binary container into a flat map of `path -> bytes`.
 *
 * The whole file is held in memory. That is the right trade for this app: a `.hwp` big enough to
 * matter is still far smaller than the bitmaps the editor will hold, and random sector access over
 * a content URI would otherwise mean re-opening the stream constantly.
 */
class CfbReader(private val data: ByteArray) {

    private lateinit var fat: IntArray
    private lateinit var miniFat: IntArray
    private var miniStream: ByteArray = ByteArray(0)
    private var sectorSize = Cfb.SECTOR_SIZE
    private var miniCutoff = Cfb.MINI_STREAM_CUTOFF

    private val streams = LinkedHashMap<String, ByteArray>()
    private val entries = ArrayList<CfbEntry>()

    init {
        parse()
    }

    /** All stream paths, e.g. `FileHeader`, `BodyText/Section0`, `BinData/BIN0001.png`. */
    fun streamNames(): List<String> = streams.keys.toList()

    fun allEntries(): List<CfbEntry> = entries.toList()

    fun hasStream(path: String): Boolean = streams.containsKey(path)

    fun read(path: String): ByteArray =
        streams[path] ?: throw HwpFormatException("문서에 '$path' 스트림이 없습니다.")

    fun readOrNull(path: String): ByteArray? = streams[path]

    /** Stream paths directly under [storage], without the storage prefix. */
    fun childrenOf(storage: String): List<String> {
        val prefix = "$storage/"
        return streams.keys.filter { it.startsWith(prefix) && !it.substring(prefix.length).contains('/') }
            .map { it.substring(prefix.length) }
    }

    private fun parse() {
        if (data.size < Cfb.SECTOR_SIZE) {
            throw HwpFormatException("파일이 너무 작아 한글 문서로 읽을 수 없습니다.")
        }
        for (i in Cfb.SIGNATURE.indices) {
            if (data[i] != Cfb.SIGNATURE[i]) {
                throw HwpFormatException("한글 문서(.hwp) 형식이 아닙니다. 복합 문서 서명이 일치하지 않습니다.")
            }
        }

        val h = ByteReader(data)
        h.skip(8 + 16) // signature + CLSID
        h.skip(2) // minor version
        val majorVersion = h.u16()
        val byteOrder = h.u16()
        if (byteOrder != 0xFFFE) throw HwpFormatException("지원하지 않는 바이트 순서입니다.")
        val sectorShift = h.u16()
        val miniSectorShift = h.u16()
        sectorSize = 1 shl sectorShift
        val miniSectorSize = 1 shl miniSectorShift
        if (majorVersion == 3 && sectorSize != 512) {
            throw HwpFormatException("복합 문서 헤더가 손상되었습니다(섹터 크기 $sectorSize).")
        }
        h.skip(6) // reserved
        h.skip(4) // directory sector count (unused in v3)
        val fatSectorCount = h.i32()
        val firstDirSector = h.i32()
        h.skip(4) // transaction signature
        miniCutoff = h.i32()
        val firstMiniFatSector = h.i32()
        val miniFatSectorCount = h.i32()
        val firstDifatSector = h.i32()
        val difatSectorCount = h.i32()

        val fatSectors = readDifat(h, firstDifatSector, difatSectorCount, fatSectorCount)
        fat = readFat(fatSectors)
        miniFat = if (firstMiniFatSector == Cfb.END_OF_CHAIN || miniFatSectorCount == 0) {
            IntArray(0)
        } else {
            val raw = readChain(firstMiniFatSector)
            IntArray(raw.size / 4) { idx ->
                ByteReader(raw, idx * 4).i32()
            }
        }

        readDirectory(firstDirSector, miniSectorSize)
    }

    /** The DIFAT is the index of FAT sectors: 109 slots inline, the rest chained through sectors. */
    private fun readDifat(header: ByteReader, firstDifatSector: Int, difatSectorCount: Int, fatSectorCount: Int): IntArray {
        val result = ArrayList<Int>(fatSectorCount.coerceAtLeast(0).coerceAtMost(1 shl 20))
        // Sector numbers are unsigned: every real one fits in a non-negative Int, and the four
        // sentinel values (DIFSECT/FATSECT/ENDOFCHAIN/FREESECT) all read back negative. Comparing
        // against MAX_REG_SECT as a signed Int would therefore reject every valid sector.
        for (i in 0 until Cfb.DIFAT_ENTRIES_IN_HEADER) {
            val sect = header.i32()
            if (sect >= 0) result.add(sect)
        }
        var next = firstDifatSector
        var guard = 0
        while (next != Cfb.END_OF_CHAIN && next != Cfb.FREE_SECT && next >= 0 && guard < difatSectorCount + 16) {
            val r = ByteReader(sectorBytes(next))
            for (i in 0 until Cfb.DIFAT_ENTRIES_PER_SECTOR) {
                val sect = r.i32()
                if (sect >= 0) result.add(sect)
            }
            next = r.i32()
            guard++
        }
        return result.toIntArray()
    }

    private fun readFat(fatSectors: IntArray): IntArray {
        val out = IntArray(fatSectors.size * (sectorSize / 4))
        var w = 0
        for (sect in fatSectors) {
            val r = ByteReader(sectorBytes(sect))
            repeat(sectorSize / 4) { out[w++] = r.i32() }
        }
        return out
    }

    private fun sectorOffset(sector: Int): Int = sectorSize + sector * sectorSize

    private fun sectorBytes(sector: Int): ByteArray {
        val off = sectorOffset(sector)
        if (off < 0 || off + sectorSize > data.size) {
            throw HwpFormatException("복합 문서의 섹터 $sector 가 파일 범위를 벗어납니다. 손상된 문서입니다.")
        }
        return data.copyOfRange(off, off + sectorSize)
    }

    /** Follows a FAT chain and concatenates its sectors. */
    private fun readChain(start: Int): ByteArray {
        if (start == Cfb.END_OF_CHAIN || start < 0) return ByteArray(0)
        val parts = ArrayList<ByteArray>()
        var sect = start
        val seen = HashSet<Int>()
        while (sect != Cfb.END_OF_CHAIN && sect != Cfb.FREE_SECT && sect >= 0) {
            if (!seen.add(sect)) throw HwpFormatException("복합 문서의 섹터 체인이 순환합니다. 손상된 문서입니다.")
            parts.add(sectorBytes(sect))
            sect = if (sect < fat.size) fat[sect] else Cfb.END_OF_CHAIN
        }
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var p = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, p, part.size)
            p += part.size
        }
        return out
    }

    private fun readMiniChain(start: Int, size: Int, miniSectorSize: Int): ByteArray {
        if (size == 0 || start < 0 || start == Cfb.END_OF_CHAIN) return ByteArray(0)
        val out = ByteArray(size)
        var sect = start
        var written = 0
        val seen = HashSet<Int>()
        while (sect != Cfb.END_OF_CHAIN && sect >= 0 && written < size) {
            if (!seen.add(sect)) throw HwpFormatException("복합 문서의 미니 섹터 체인이 순환합니다.")
            val off = sect * miniSectorSize
            if (off >= miniStream.size) break
            val n = minOf(miniSectorSize, size - written, miniStream.size - off)
            System.arraycopy(miniStream, off, out, written, n)
            written += n
            sect = if (sect < miniFat.size) miniFat[sect] else Cfb.END_OF_CHAIN
        }
        return out
    }

    private fun readDirectory(firstDirSector: Int, miniSectorSize: Int) {
        val dir = readChain(firstDirSector)
        val count = dir.size / Cfb.DIRECTORY_ENTRY_SIZE
        if (count == 0) throw HwpFormatException("복합 문서의 디렉터리가 비어 있습니다.")

        data class Raw(
            val name: String, val type: Int, val left: Int, val right: Int,
            val child: Int, val start: Int, val size: Long,
        )

        val raws = ArrayList<Raw>(count)
        for (i in 0 until count) {
            val r = ByteReader(dir, i * Cfb.DIRECTORY_ENTRY_SIZE)
            val nameBytes = r.bytes(64)
            val nameLen = r.u16()
            val type = r.u8()
            r.u8() // colour
            val left = r.i32()
            val right = r.i32()
            val child = r.i32()
            r.skip(16) // CLSID
            r.skip(4) // state bits
            r.skip(8) // creation time
            r.skip(8) // modified time
            val start = r.i32()
            val size = r.i64()
            val chars = ((nameLen / 2) - 1).coerceIn(0, 32)
            val sb = StringBuilder(chars)
            for (c in 0 until chars) {
                val unit = (nameBytes[c * 2].toInt() and 0xFF) or ((nameBytes[c * 2 + 1].toInt() and 0xFF) shl 8)
                if (unit == 0) break
                sb.append(unit.toChar())
            }
            raws.add(Raw(sb.toString(), type, left, right, child, start, size))
        }

        // The root entry owns the mini stream; it has to be materialised before any small stream.
        val root = raws[0]
        miniStream = readChain(root.start)

        // Walk the directory tree depth-first so nested storages get a proper path.
        val visited = HashSet<Int>()
        fun walk(id: Int, prefix: String) {
            if (id == Cfb.NO_STREAM || id < 0 || id >= raws.size) return
            if (!visited.add(id)) return
            val e = raws[id]
            walk(e.left, prefix)
            when (e.type) {
                Cfb.TYPE_STORAGE -> {
                    val path = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
                    entries.add(CfbEntry(e.name, e.type, path))
                    walk(e.child, path)
                }
                Cfb.TYPE_STREAM -> {
                    val path = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
                    val size = e.size.toInt().coerceAtLeast(0)
                    val bytes = if (e.size < miniCutoff) {
                        readMiniChain(e.start, size, Cfb.MINI_SECTOR_SIZE)
                    } else {
                        val full = readChain(e.start)
                        if (full.size > size) full.copyOf(size) else full
                    }
                    entries.add(CfbEntry(e.name, e.type, path))
                    streams[path] = bytes
                }
            }
            walk(e.right, prefix)
        }
        walk(root.child, "")
    }
}
