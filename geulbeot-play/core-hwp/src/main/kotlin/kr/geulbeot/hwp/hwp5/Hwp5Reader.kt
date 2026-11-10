package kr.geulbeot.hwp.hwp5

import kr.geulbeot.hwp.cfb.CfbReader
import kr.geulbeot.hwp.model.DocumentFormat
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.RestrictedDocumentException
import kr.geulbeot.hwp.model.Section
import kr.geulbeot.hwp.record.HwpRecordCodec
import kr.geulbeot.hwp.util.Compression
import kr.geulbeot.hwp.util.HwpFormatException
import kr.geulbeot.hwp.util.decodeUtf16Le

/** Reads a `.hwp` (HWP 5.0 binary) document into the shared document model. */
object Hwp5Reader {

    const val STREAM_FILE_HEADER = "FileHeader"
    const val STREAM_DOC_INFO = "DocInfo"
    const val STORAGE_BODY_TEXT = "BodyText"
    const val STORAGE_VIEW_TEXT = "ViewText"
    const val STORAGE_BIN_DATA = "BinData"
    const val STREAM_PREVIEW_TEXT = "PrvText"

    fun read(bytes: ByteArray): HwpDocument {
        val cfb = CfbReader(bytes)
        val header = FileHeader.parse(
            cfb.readOrNull(STREAM_FILE_HEADER)
                ?: throw HwpFormatException("한글 문서에 FileHeader가 없습니다. 다른 형식의 파일로 보입니다."),
        )
        header.restriction()?.let { throw RestrictedDocumentException(it) }

        val document = HwpDocument()
        document.format = DocumentFormat.HWP5
        document.version = header.version
        document.compressed = header.compressed

        val docInfoBytes = cfb.readOrNull(STREAM_DOC_INFO)
            ?: throw HwpFormatException("한글 문서에 DocInfo가 없습니다. 손상된 문서입니다.")
        val docInfoRecords = HwpRecordCodec.parse(maybeInflate(docInfoBytes, header.compressed))
        DocInfoCodec.read(document, docInfoRecords, header)

        // 한글 writes body text under BodyText; a distribution document uses ViewText instead, and
        // those are refused above, but a document may still carry both.
        val storage = if (cfb.childrenOf(STORAGE_BODY_TEXT).isNotEmpty()) STORAGE_BODY_TEXT else STORAGE_VIEW_TEXT
        val sectionNames = cfb.childrenOf(storage)
            .filter { it.startsWith("Section") }
            .sortedBy { it.removePrefix("Section").toIntOrNull() ?: Int.MAX_VALUE }

        for (name in sectionNames) {
            val raw = cfb.read("$storage/$name")
            val records = HwpRecordCodec.parse(maybeInflate(raw, header.compressed))
            document.sections.add(BodyTextCodec.readSection(records))
        }
        if (document.sections.isEmpty()) document.sections.add(Section())

        readBinData(cfb, document, header.compressed)

        cfb.readOrNull(SummaryInformation.STREAM_NAME)?.let {
            document.summary = SummaryInformation.read(it)
        }

        document.sourceStreams = cfb.streamNames().associateWith { cfb.read(it) }
        document.markClean()
        return document
    }

    /** A fast path for search and previews: the plain-text stream 한글 keeps for its own preview. */
    fun readPreviewText(bytes: ByteArray): String? = try {
        CfbReader(bytes).readOrNull(STREAM_PREVIEW_TEXT)?.let { decodeUtf16Le(it) }
    } catch (_: Exception) {
        null
    }

    private fun readBinData(cfb: CfbReader, document: HwpDocument, compressed: Boolean) {
        val available = cfb.childrenOf(STORAGE_BIN_DATA)
        if (available.isEmpty()) return
        for (entry in document.binData) {
            val name = entry.streamName ?: continue
            // Stored ids are hexadecimal and the extension recorded in DocInfo may differ in case.
            val match = available.firstOrNull { it.equals(name, ignoreCase = true) }
                ?: available.firstOrNull { it.substringBeforeLast('.').equals(name.substringBeforeLast('.'), true) }
                ?: continue
            val raw = cfb.read("$STORAGE_BIN_DATA/$match")
            entry.data = if (compressed) {
                runCatching { Compression.inflateBinData(raw) }.getOrDefault(raw)
            } else {
                raw
            }
            entry.streamName = match
        }
    }

    private fun maybeInflate(data: ByteArray, compressed: Boolean): ByteArray =
        if (compressed) Compression.inflateRaw(data) else data

    /** True when these bytes look like an HWP 5 document, without fully parsing them. */
    fun looksLikeHwp5(bytes: ByteArray): Boolean = try {
        val cfb = CfbReader(bytes)
        val header = cfb.readOrNull(STREAM_FILE_HEADER)
        header != null && String(header, 0, FileHeader.SIGNATURE.length, Charsets.US_ASCII) == FileHeader.SIGNATURE
    } catch (_: Exception) {
        false
    }
}
