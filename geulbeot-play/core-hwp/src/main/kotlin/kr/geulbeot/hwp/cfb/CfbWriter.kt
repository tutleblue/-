package kr.geulbeot.hwp.cfb

import kr.geulbeot.hwp.util.ByteWriter

/**
 * Builds a Compound File Binary container from a set of named streams.
 *
 * Layout is chosen rather than preserved, which keeps this simple: data sectors first, then the FAT,
 * then the DIFAT if the document is large enough to need one. Timestamps are written as zero on
 * purpose - creation and modification times are the one place a container like this leaks
 * information about the person who made the file, and nothing in HWP needs them.
 */
class CfbWriter {

    private val streams = LinkedHashMap<String, ByteArray>()

    fun put(path: String, data: ByteArray) = apply { streams[path] = data }

    fun putAll(all: Map<String, ByteArray>) = apply { streams.putAll(all) }

    fun isEmpty(): Boolean = streams.isEmpty()

    // ---- directory tree -------------------------------------------------------------------

    private class Node(val name: String, val isStream: Boolean) {
        var data: ByteArray = ByteArray(0)
        val children = LinkedHashMap<String, Node>()
        var id: Int = -1
        var left: Int = Cfb.NO_STREAM
        var right: Int = Cfb.NO_STREAM
        var child: Int = Cfb.NO_STREAM
        var startSector: Int = Cfb.END_OF_CHAIN
        var byteSize: Long = 0
    }

    fun build(): ByteArray {
        val root = Node("Root Entry", isStream = false)
        for ((path, data) in streams) {
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            var cur = root
            for (i in 0 until parts.size - 1) {
                cur = cur.children.getOrPut(parts[i]) { Node(parts[i], isStream = false) }
            }
            val leaf = Node(parts.last(), isStream = true).also { it.data = data; it.byteSize = data.size.toLong() }
            cur.children[leaf.name] = leaf
        }

        // Assign ids: root is always 0, then every other node in a stable depth-first order.
        val ordered = ArrayList<Node>()
        ordered.add(root)
        root.id = 0
        fun assign(n: Node) {
            for (c in n.children.values.sortedWith(compareBy(Cfb.NAME_ORDER) { it.name })) {
                c.id = ordered.size
                ordered.add(c)
                assign(c)
            }
        }
        assign(root)

        // Each storage points at the root of a balanced tree over its children.
        fun linkChildren(n: Node) {
            val kids = n.children.values.sortedWith(compareBy(Cfb.NAME_ORDER) { it.name })
            n.child = buildBalanced(kids, 0, kids.size - 1)
            for (k in kids) linkChildren(k)
        }
        linkChildren(root)

        // ---- split streams between the mini stream and full sectors -------------------------
        val miniStream = ByteWriter(4096)
        val bigStreams = ArrayList<Node>()
        for (n in ordered) {
            if (!n.isStream) continue
            when {
                n.data.isEmpty() -> {
                    n.startSector = Cfb.END_OF_CHAIN
                    n.byteSize = 0
                }
                n.data.size < Cfb.MINI_STREAM_CUTOFF -> {
                    n.startSector = miniStream.size / Cfb.MINI_SECTOR_SIZE
                    miniStream.bytes(n.data)
                    val pad = (-n.data.size).mod(Cfb.MINI_SECTOR_SIZE)
                    if (pad > 0) miniStream.zeros(pad)
                }
                else -> bigStreams.add(n)
            }
        }
        val miniStreamData = miniStream.toByteArray()
        val miniSectorCount = miniStreamData.size / Cfb.MINI_SECTOR_SIZE

        // ---- allocate regular sectors -------------------------------------------------------
        val chains = ArrayList<IntArray>() // each chain is a run of consecutive sector numbers
        var nextSector = 0
        fun allocate(byteLength: Int): IntArray {
            val count = ceilDiv(byteLength, Cfb.SECTOR_SIZE)
            if (count == 0) return IntArray(0)
            val run = IntArray(count) { nextSector + it }
            nextSector += count
            chains.add(run)
            return run
        }

        for (n in bigStreams) {
            val run = allocate(n.data.size)
            n.startSector = if (run.isEmpty()) Cfb.END_OF_CHAIN else run[0]
        }
        val miniStreamRun = allocate(miniStreamData.size)
        root.startSector = if (miniStreamRun.isEmpty()) Cfb.END_OF_CHAIN else miniStreamRun[0]
        root.byteSize = miniStreamData.size.toLong()

        val miniFatByteLength = miniSectorCount * 4
        val miniFatRun = allocate(miniFatByteLength)

        val dirEntryCount = ordered.size
        val dirSectorCount = ceilDiv(dirEntryCount * Cfb.DIRECTORY_ENTRY_SIZE, Cfb.SECTOR_SIZE)
        val dirRun = allocate(dirSectorCount * Cfb.SECTOR_SIZE)

        val dataSectorCount = nextSector

        // ---- size the FAT (and the DIFAT, if the FAT outgrows the 109 header slots) ----------
        var fatSectorCount = 1
        var difatSectorCount = 0
        while (true) {
            difatSectorCount = if (fatSectorCount <= Cfb.DIFAT_ENTRIES_IN_HEADER) {
                0
            } else {
                ceilDiv(fatSectorCount - Cfb.DIFAT_ENTRIES_IN_HEADER, Cfb.DIFAT_ENTRIES_PER_SECTOR)
            }
            val total = dataSectorCount + fatSectorCount + difatSectorCount
            if (fatSectorCount.toLong() * Cfb.FAT_ENTRIES_PER_SECTOR >= total) break
            fatSectorCount++
        }
        val fatStart = dataSectorCount
        val difatStart = fatStart + fatSectorCount
        val totalSectors = dataSectorCount + fatSectorCount + difatSectorCount

        val fat = IntArray(fatSectorCount * Cfb.FAT_ENTRIES_PER_SECTOR) { Cfb.FREE_SECT }
        for (run in chains) {
            for (i in run.indices) {
                fat[run[i]] = if (i == run.size - 1) Cfb.END_OF_CHAIN else run[i + 1]
            }
        }
        for (i in 0 until fatSectorCount) fat[fatStart + i] = Cfb.FATSECT
        for (i in 0 until difatSectorCount) fat[difatStart + i] = Cfb.DIFSECT

        // The mini FAT chains mini sectors; every small stream here is laid out contiguously, so each
        // mini sector simply points at the next one unless it ends a stream.
        val miniFat = IntArray(miniSectorCount) { Cfb.END_OF_CHAIN }
        for (n in ordered) {
            if (!n.isStream || n.data.isEmpty() || n.data.size >= Cfb.MINI_STREAM_CUTOFF) continue
            val count = ceilDiv(n.data.size, Cfb.MINI_SECTOR_SIZE)
            for (i in 0 until count) {
                val sect = n.startSector + i
                miniFat[sect] = if (i == count - 1) Cfb.END_OF_CHAIN else sect + 1
            }
        }

        // ---- emit -----------------------------------------------------------------------------
        val out = ByteWriter(Cfb.SECTOR_SIZE * (totalSectors + 1))
        writeHeader(
            out,
            fatSectorCount = fatSectorCount,
            firstDirSector = if (dirRun.isEmpty()) Cfb.END_OF_CHAIN else dirRun[0],
            firstMiniFatSector = if (miniFatRun.isEmpty()) Cfb.END_OF_CHAIN else miniFatRun[0],
            miniFatSectorCount = miniFatRun.size,
            firstDifatSector = if (difatSectorCount == 0) Cfb.END_OF_CHAIN else difatStart,
            difatSectorCount = difatSectorCount,
            fatStart = fatStart,
        )

        for (n in bigStreams) writePadded(out, n.data)
        writePadded(out, miniStreamData)
        if (miniFatRun.isNotEmpty()) {
            val w = ByteWriter(miniFatRun.size * Cfb.SECTOR_SIZE)
            for (v in miniFat) w.i32(v)
            writePaddedWith(out, w.toByteArray(), miniFatRun.size * Cfb.SECTOR_SIZE, Cfb.FREE_SECT)
        }
        if (dirRun.isNotEmpty()) {
            val w = ByteWriter(dirSectorCount * Cfb.SECTOR_SIZE)
            for (n in ordered) writeDirectoryEntry(w, n, isRoot = n === root)
            // Pad out the last directory sector with properly-formed unallocated entries rather
            // than zeros: a zeroed entry claims entry 0 as its left sibling, which other readers
            // are entitled to follow.
            val slack = dirSectorCount * Cfb.ENTRIES_PER_SECTOR - ordered.size
            repeat(slack) { writeUnallocatedEntry(w) }
            out.bytes(w.toByteArray())
        }

        run {
            val w = ByteWriter(fatSectorCount * Cfb.SECTOR_SIZE)
            for (v in fat) w.i32(v)
            out.bytes(w.toByteArray())
        }

        if (difatSectorCount > 0) {
            val w = ByteWriter(difatSectorCount * Cfb.SECTOR_SIZE)
            var fatIndex = Cfb.DIFAT_ENTRIES_IN_HEADER
            for (s in 0 until difatSectorCount) {
                for (i in 0 until Cfb.DIFAT_ENTRIES_PER_SECTOR) {
                    w.i32(if (fatIndex < fatSectorCount) fatStart + fatIndex++ else Cfb.FREE_SECT)
                }
                w.i32(if (s == difatSectorCount - 1) Cfb.END_OF_CHAIN else difatStart + s + 1)
            }
            out.bytes(w.toByteArray())
        }

        return out.toByteArray()
    }

    private fun buildBalanced(kids: List<Node>, lo: Int, hi: Int): Int {
        if (lo > hi) return Cfb.NO_STREAM
        val mid = (lo + hi) / 2
        val node = kids[mid]
        node.left = buildBalanced(kids, lo, mid - 1)
        node.right = buildBalanced(kids, mid + 1, hi)
        return node.id
    }

    private fun writeHeader(
        out: ByteWriter,
        fatSectorCount: Int,
        firstDirSector: Int,
        firstMiniFatSector: Int,
        miniFatSectorCount: Int,
        firstDifatSector: Int,
        difatSectorCount: Int,
        fatStart: Int,
    ) {
        out.bytes(Cfb.SIGNATURE)
        out.zeros(16)          // CLSID
        out.u16(0x003E)        // minor version
        out.u16(3)             // major version: 512-byte sectors
        out.u16(0xFFFE)        // little-endian marker
        out.u16(9)             // sector shift: 1 << 9 == 512
        out.u16(6)             // mini sector shift: 1 << 6 == 64
        out.zeros(6)           // reserved
        out.i32(0)             // directory sector count (unused in version 3)
        out.i32(fatSectorCount)
        out.i32(firstDirSector)
        out.i32(0)             // transaction signature
        out.i32(Cfb.MINI_STREAM_CUTOFF)
        out.i32(firstMiniFatSector)
        out.i32(miniFatSectorCount)
        out.i32(firstDifatSector)
        out.i32(difatSectorCount)
        for (i in 0 until Cfb.DIFAT_ENTRIES_IN_HEADER) {
            out.i32(if (i < fatSectorCount) fatStart + i else Cfb.FREE_SECT)
        }
    }

    private fun writeDirectoryEntry(w: ByteWriter, n: Node, isRoot: Boolean) {
        val name = if (isRoot) "Root Entry" else n.name
        val chars = name.length.coerceAtMost(31)
        val nameBytes = ByteArray(64)
        for (i in 0 until chars) {
            val c = name[i].code
            nameBytes[i * 2] = (c and 0xFF).toByte()
            nameBytes[i * 2 + 1] = ((c ushr 8) and 0xFF).toByte()
        }
        w.bytes(nameBytes)
        w.u16((chars + 1) * 2)
        w.u8(
            when {
                isRoot -> Cfb.TYPE_ROOT
                n.isStream -> Cfb.TYPE_STREAM
                else -> Cfb.TYPE_STORAGE
            },
        )
        w.u8(Cfb.COLOR_BLACK)
        w.i32(if (isRoot) Cfb.NO_STREAM else n.left)
        w.i32(if (isRoot) Cfb.NO_STREAM else n.right)
        w.i32(n.child)
        w.zeros(16)   // CLSID
        w.i32(0)      // state bits
        w.zeros(8)    // creation time - intentionally zero, see class doc
        w.zeros(8)    // modified time - intentionally zero
        w.i32(n.startSector)
        w.u32(n.byteSize)
        w.i32(0)      // high half of the 64-bit size; HWP documents never reach 4 GiB
    }

    private fun writeUnallocatedEntry(w: ByteWriter) {
        w.zeros(64)                 // name
        w.u16(0)                    // name length
        w.u8(Cfb.TYPE_UNALLOCATED)
        w.u8(Cfb.COLOR_RED)
        w.i32(Cfb.NO_STREAM)
        w.i32(Cfb.NO_STREAM)
        w.i32(Cfb.NO_STREAM)
        w.zeros(16)                 // CLSID
        w.i32(0)                    // state bits
        w.zeros(8)                  // creation time
        w.zeros(8)                  // modified time
        w.i32(0)                    // start sector
        w.zeros(8)                  // size
    }

    private fun writePadded(out: ByteWriter, data: ByteArray) {
        if (data.isEmpty()) return
        out.bytes(data)
        val pad = (-data.size).mod(Cfb.SECTOR_SIZE)
        if (pad > 0) out.zeros(pad)
    }

    private fun writePaddedWith(out: ByteWriter, data: ByteArray, totalBytes: Int, fillWord: Int) {
        out.bytes(data)
        var remaining = totalBytes - data.size
        while (remaining >= 4) {
            out.i32(fillWord)
            remaining -= 4
        }
        if (remaining > 0) out.zeros(remaining)
    }

    private fun ceilDiv(a: Int, b: Int): Int = if (a <= 0) 0 else (a + b - 1) / b
}
