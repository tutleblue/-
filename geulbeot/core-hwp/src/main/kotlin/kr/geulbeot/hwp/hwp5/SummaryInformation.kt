package kr.geulbeot.hwp.hwp5

import kr.geulbeot.hwp.model.DocumentSummary
import kr.geulbeot.hwp.util.ByteReader
import kr.geulbeot.hwp.util.ByteWriter
import kr.geulbeot.hwp.util.decodeUtf16Le

/**
 * The `U+0005HwpSummaryInformation` stream: title, author, keywords and so on, as shown in 한글's
 * 문서 정보 dialog.
 *
 * It is an OLE property set, the same structure Office documents use. Only string properties are
 * read and written; the timestamps and statistics Office stores there are not something a mobile
 * editor should be inventing.
 */
object SummaryInformation {

    /** Stream name. The leading character is U+0005, which is part of the name, not a separator. */
    val STREAM_NAME: String = Char(5) + "HwpSummaryInformation"

    private const val PID_TITLE = 2
    private const val PID_SUBJECT = 3
    private const val PID_AUTHOR = 4
    private const val PID_KEYWORDS = 5
    private const val PID_COMMENTS = 6
    private const val PID_LAST_AUTHOR = 8

    /**
     * OLE strings are NUL-terminated and the terminator counts towards the declared length.
     * Spelled with Char(0) so no control character ends up in this file.
     */
    private val NUL: Char = Char(0)

    private const val VT_LPSTR = 30
    private const val VT_LPWSTR = 31

    /** FMTID for a summary information property set. */
    private val FMTID = byteArrayOf(
        0xE0.toByte(), 0x85.toByte(), 0x9F.toByte(), 0xF2.toByte(),
        0xF9.toByte(), 0x4F, 0x68, 0x10,
        0xAB.toByte(), 0x91.toByte(), 0x08, 0x00,
        0x2B, 0x27, 0xB3.toByte(), 0xD9.toByte(),
    )

    fun read(data: ByteArray): DocumentSummary {
        val summary = DocumentSummary()
        try {
            val header = ByteReader(data)
            header.u16()          // byte order marker
            header.u16()          // format
            header.i32()          // OS version
            header.skip(16)       // CLSID
            val setCount = header.i32()
            if (setCount < 1) return summary
            header.skip(16)       // FMTID of the first set
            val sectionOffset = header.i32()
            if (sectionOffset < 0 || sectionOffset >= data.size) return summary

            val section = ByteReader(data, sectionOffset)
            section.i32()         // section byte length
            val propertyCount = section.i32()
            val entries = ArrayList<Pair<Int, Int>>(propertyCount.coerceIn(0, 512))
            repeat(propertyCount.coerceIn(0, 512)) {
                val id = section.i32()
                val offset = section.i32()
                entries.add(id to offset)
            }

            for ((id, offset) in entries) {
                val absolute = sectionOffset + offset
                if (absolute < 0 || absolute + 4 > data.size) continue
                val value = readStringProperty(data, absolute) ?: continue
                when (id) {
                    PID_TITLE -> summary.title = value
                    PID_SUBJECT -> summary.subject = value
                    PID_AUTHOR -> summary.author = value
                    PID_KEYWORDS -> summary.keywords = value
                    PID_COMMENTS -> summary.comments = value
                    PID_LAST_AUTHOR -> summary.lastSavedBy = value
                }
            }
        } catch (_: Exception) {
            // Metadata is never worth failing an open over.
        }
        return summary
    }

    private fun readStringProperty(data: ByteArray, offset: Int): String? {
        val r = ByteReader(data, offset)
        return when (r.i32()) {
            VT_LPWSTR -> {
                val chars = r.i32()
                if (chars <= 0 || chars > 1 shl 20 || r.remaining < chars * 2) return null
                decodeUtf16Le(r.bytes(chars * 2)).trimEnd(NUL)
            }
            VT_LPSTR -> {
                val length = r.i32()
                if (length <= 0 || length > 1 shl 20 || r.remaining < length) return null
                String(r.bytes(length), Charsets.UTF_8).trimEnd(NUL)
            }
            else -> null
        }
    }

    fun write(summary: DocumentSummary): ByteArray {
        val properties = buildList {
            if (summary.title.isNotEmpty()) add(PID_TITLE to summary.title)
            if (summary.subject.isNotEmpty()) add(PID_SUBJECT to summary.subject)
            if (summary.author.isNotEmpty()) add(PID_AUTHOR to summary.author)
            if (summary.keywords.isNotEmpty()) add(PID_KEYWORDS to summary.keywords)
            if (summary.comments.isNotEmpty()) add(PID_COMMENTS to summary.comments)
            if (summary.lastSavedBy.isNotEmpty()) add(PID_LAST_AUTHOR to summary.lastSavedBy)
        }

        // Lay the values out first so the property table can point at them.
        val values = ByteWriter(256)
        val offsets = ArrayList<Int>(properties.size)
        val tableBytes = 8 + properties.size * 8
        for ((_, text) in properties) {
            offsets.add(tableBytes + values.size)
            values.i32(VT_LPWSTR)
            val terminated = text + NUL
            values.i32(terminated.length)
            for (c in terminated) values.u16(c.code)
            // Property values are aligned to four bytes.
            val pad = (-(terminated.length * 2)).mod(4)
            if (pad > 0) values.zeros(pad)
        }

        val section = ByteWriter(tableBytes + values.size)
        section.i32(tableBytes + values.size)
        section.i32(properties.size)
        for (i in properties.indices) {
            section.i32(properties[i].first)
            section.i32(offsets[i])
        }
        section.bytes(values.toByteArray())

        val out = ByteWriter(48 + section.size)
        out.u16(0xFFFE)   // byte order
        out.u16(0)        // format version
        out.i32(0)        // OS version - deliberately not the running platform
        out.zeros(16)     // CLSID
        out.i32(1)        // one property set
        out.bytes(FMTID)
        out.i32(48)       // offset of the section that follows
        out.bytes(section.toByteArray())
        return out.toByteArray()
    }
}
