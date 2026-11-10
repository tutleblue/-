package kr.geulbeot.hwp.hwp5

import kr.geulbeot.hwp.cfb.CfbWriter
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.record.HwpRecordCodec
import kr.geulbeot.hwp.util.Compression
import kr.geulbeot.hwp.util.encodeUtf16Le

/**
 * Writes the document model back out as a `.hwp` (HWP 5.0 binary) file.
 *
 * Streams this app understands are regenerated; every other stream the source document carried -
 * scripts, document options, XML templates, version history - is copied across byte for byte. A
 * document created in this app rather than opened gets only the streams it actually needs.
 */
object Hwp5Writer {

    /** Streams regenerated on every save; anything else in the source is passed through. */
    private val REGENERATED = setOf(
        Hwp5Reader.STREAM_FILE_HEADER,
        Hwp5Reader.STREAM_DOC_INFO,
        Hwp5Reader.STREAM_PREVIEW_TEXT,
        SummaryInformation.STREAM_NAME,
    )

    fun write(document: HwpDocument): ByteArray {
        val compressed = document.compressed
        val version = if (document.version == 0) HwpDocument.DEFAULT_VERSION else document.version
        val writer = CfbWriter()

        val sourceStreams = document.sourceStreams.orEmpty()

        // Anything from the source we neither regenerate nor own: keep it exactly as it was.
        for ((name, data) in sourceStreams) {
            if (name in REGENERATED) continue
            if (name.startsWith("${Hwp5Reader.STORAGE_BODY_TEXT}/")) continue
            if (name.startsWith("${Hwp5Reader.STORAGE_BIN_DATA}/")) continue
            writer.put(name, data)
        }

        val header = FileHeader.create(version, compressed)
        writer.put(Hwp5Reader.STREAM_FILE_HEADER, header.raw)

        val docInfo = HwpRecordCodec.serialize(DocInfoCodec.write(document))
        writer.put(Hwp5Reader.STREAM_DOC_INFO, deflateIf(docInfo, compressed))

        for ((index, section) in document.sections.withIndex()) {
            // A document created in this app has no section-definition control yet; without one
            // 한글 has no page setup to lay the section out with.
            Hwp5Template.ensureSectionDef(section)
            val records = BodyTextCodec.writeSection(section)
            val bytes = HwpRecordCodec.serialize(records)
            writer.put("${Hwp5Reader.STORAGE_BODY_TEXT}/Section$index", deflateIf(bytes, compressed))
        }

        for (entry in document.binData) {
            val data = entry.data ?: continue
            val name = entry.streamName ?: String.format("BIN%04X.%s", entry.id, entry.extension)
            writer.put("${Hwp5Reader.STORAGE_BIN_DATA}/$name", deflateIf(data, compressed))
        }

        writer.put(Hwp5Reader.STREAM_PREVIEW_TEXT, encodeUtf16Le(previewText(document)))

        val summary = document.summary
        if (summary != kr.geulbeot.hwp.model.DocumentSummary()) {
            writer.put(SummaryInformation.STREAM_NAME, SummaryInformation.write(summary))
        }

        return writer.build()
    }

    /** 한글 keeps roughly the first page of text as a preview; a couple of thousand characters is plenty. */
    private fun previewText(document: HwpDocument): String {
        val text = document.plainText()
        return if (text.length <= PREVIEW_LIMIT) text else text.substring(0, PREVIEW_LIMIT)
    }

    private const val PREVIEW_LIMIT = 2000

    private fun deflateIf(data: ByteArray, compressed: Boolean): ByteArray =
        if (compressed) Compression.deflateRaw(data) else data
}
