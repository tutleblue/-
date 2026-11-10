package kr.geulbeot.hwp.api

import kr.geulbeot.hwp.hwp5.Hwp5Reader
import kr.geulbeot.hwp.hwp5.Hwp5Writer
import kr.geulbeot.hwp.hwpx.HwpxReader
import kr.geulbeot.hwp.hwpx.HwpxWriter
import kr.geulbeot.hwp.model.DocumentFormat
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.text.PlainTextCodec
import kr.geulbeot.hwp.util.HwpFormatException

/**
 * The entry point the app uses. Everything below this is format detail.
 *
 * Format is detected from the bytes rather than the file name: a document shared from a messaging
 * app often arrives with the wrong extension or none at all, and guessing wrong would show the user
 * a parse error for a perfectly good file.
 */
object GeulbeotDocuments {

    fun detectFormat(bytes: ByteArray, fileName: String? = null): DocumentFormat? = when {
        Hwp5Reader.looksLikeHwp5(bytes) -> DocumentFormat.HWP5
        HwpxReader.looksLikeHwpx(bytes) -> DocumentFormat.HWPX
        fileName != null -> DocumentFormat.fromFileName(fileName)
        else -> null
    }

    /** Opens a document, throwing [HwpFormatException] with a message meant for the user. */
    fun open(bytes: ByteArray, fileName: String? = null): HwpDocument = when (detectFormat(bytes, fileName)) {
        DocumentFormat.HWP5 -> Hwp5Reader.read(bytes)
        DocumentFormat.HWPX -> HwpxReader.read(bytes)
        DocumentFormat.PLAIN_TEXT -> PlainTextCodec.read(bytes)
        null -> throw HwpFormatException(
            "한글 문서로 읽을 수 없는 파일입니다. .hwp, .hwpx, .txt 파일을 열 수 있습니다.",
        )
    }

    fun save(document: HwpDocument, format: DocumentFormat = document.format): ByteArray = when (format) {
        DocumentFormat.HWP5 -> Hwp5Writer.write(document)
        DocumentFormat.HWPX -> HwpxWriter.write(document)
        DocumentFormat.PLAIN_TEXT -> PlainTextCodec.write(document)
    }

    fun newDocument(): HwpDocument = HwpDocument.blank()

    /**
     * Text only, as cheaply as the format allows.
     *
     * Both HWP formats keep a plain-text preview, so a document list can show a snippet without
     * parsing the body at all.
     */
    fun extractText(bytes: ByteArray, fileName: String? = null, quick: Boolean = false): String {
        if (quick) {
            when (detectFormat(bytes, fileName)) {
                DocumentFormat.HWP5 -> Hwp5Reader.readPreviewText(bytes)?.let { return it }
                DocumentFormat.HWPX -> HwpxReader.readPreviewText(bytes)?.let { return it }
                DocumentFormat.PLAIN_TEXT -> return PlainTextCodec.decode(bytes)
                null -> Unit
            }
        }
        return open(bytes, fileName).plainText()
    }

    /** Converts between formats in one step; the model is shared, so there is nothing else to do. */
    fun convert(bytes: ByteArray, fileName: String?, target: DocumentFormat): ByteArray =
        save(open(bytes, fileName), target)

    /**
     * The format a document should be saved back to by default.
     *
     * A document opened from `.hwp` goes back to `.hwp` so the user's file keeps working where it
     * came from. Anything else - including a new document - defaults to `.hwpx`, which is the
     * published standard and the format this app can write without reconstructing anything.
     */
    fun defaultSaveFormat(document: HwpDocument): DocumentFormat = when (document.format) {
        DocumentFormat.HWP5 -> DocumentFormat.HWP5
        else -> DocumentFormat.HWPX
    }
}
