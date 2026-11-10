package kr.geulbeot.hwp.record

import kr.geulbeot.hwp.util.ByteReader
import kr.geulbeot.hwp.util.ByteWriter
import kr.geulbeot.hwp.util.HwpFormatException

/**
 * One record inside a DocInfo or BodyText stream, with its children.
 *
 * The record header packs three fields into a single little-endian UINT32:
 *
 * ```
 *   bits  0-9   tag id      (0x000 - 0x3FF, HWPTAG_BEGIN is 0x010)
 *   bits 10-19  nesting level
 *   bits 20-31  payload size; the escape value 0xFFF means "the next UINT32 is the real size"
 * ```
 *
 * Records arrive as a flat list and form a tree by level. Keeping the whole tree - including tags
 * this app has no interest in - is what lets a document be saved back without losing the parts of
 * HWP 5.0 that are not implemented here.
 */
class HwpRecord(
    val tagId: Int,
    val level: Int,
    var payload: ByteArray,
    val children: MutableList<HwpRecord> = ArrayList(),
) {
    /**
     * True when the source file spelled the size out in the extended 4-byte form even though it
     * would have fit inline. Remembering this is what makes the byte-for-byte round trip exact.
     */
    var forcedExtendedSize: Boolean = false

    val size: Int get() = payload.size

    fun reader(): ByteReader = ByteReader(payload)

    fun firstChild(tag: Int): HwpRecord? = children.firstOrNull { it.tagId == tag }

    fun childrenWithTag(tag: Int): List<HwpRecord> = children.filter { it.tagId == tag }

    /** Depth-first walk over this record and everything under it. */
    fun forEachDeep(action: (HwpRecord) -> Unit) {
        action(this)
        for (c in children) c.forEachDeep(action)
    }

    fun deepCopy(): HwpRecord {
        val copy = HwpRecord(tagId, level, payload.copyOf())
        copy.forcedExtendedSize = forcedExtendedSize
        for (c in children) copy.children.add(c.deepCopy())
        return copy
    }

    override fun toString(): String = "HwpRecord(tag=0x${tagId.toString(16)}, level=$level, size=$size, children=${children.size})"
}

object HwpRecordCodec {

    private const val TAG_MASK = 0x3FF
    private const val LEVEL_MASK = 0x3FF
    private const val SIZE_MASK = 0xFFF
    private const val EXTENDED_SIZE = 0xFFF

    /** Parses a decompressed record stream into a forest of top-level records. */
    fun parse(data: ByteArray): List<HwpRecord> {
        val reader = ByteReader(data)
        val roots = ArrayList<HwpRecord>()
        // stack[i] is the record most recently opened at level i, i.e. the parent for level i+1.
        val stack = ArrayList<HwpRecord>()

        while (reader.remaining >= 4) {
            val header = reader.i32()
            val tagId = header and TAG_MASK
            val level = (header ushr 10) and LEVEL_MASK
            var size = (header ushr 20) and SIZE_MASK
            var extended = false
            if (size == EXTENDED_SIZE) {
                if (reader.remaining < 4) break
                size = reader.i32()
                extended = true
            }
            if (size < 0 || size > reader.remaining) {
                throw HwpFormatException(
                    "레코드 크기가 스트림 범위를 벗어납니다(tag=0x${tagId.toString(16)}, size=$size). 손상된 문서입니다.",
                )
            }
            val record = HwpRecord(tagId, level, reader.bytes(size))
            record.forcedExtendedSize = extended && size < EXTENDED_SIZE

            while (stack.size > level) stack.removeAt(stack.size - 1)
            if (level == 0 || stack.isEmpty()) {
                roots.add(record)
            } else {
                stack[stack.size - 1].children.add(record)
            }
            stack.add(record)
        }
        return roots
    }

    /** Serialises a forest back into a record stream. */
    fun serialize(roots: List<HwpRecord>): ByteArray {
        val out = ByteWriter(4096)
        for (r in roots) write(out, r, r.level)
        return out.toByteArray()
    }

    private fun write(out: ByteWriter, record: HwpRecord, level: Int) {
        val size = record.payload.size
        val useExtended = size >= EXTENDED_SIZE || record.forcedExtendedSize
        val inlineSize = if (useExtended) EXTENDED_SIZE else size
        val header = (record.tagId and TAG_MASK) or
            ((level and LEVEL_MASK) shl 10) or
            ((inlineSize and SIZE_MASK) shl 20)
        out.i32(header)
        if (useExtended) out.i32(size)
        out.bytes(record.payload)
        for (c in record.children) write(out, c, level + 1)
    }
}
