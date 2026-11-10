package kr.geulbeot.hwp.text

import kr.geulbeot.hwp.model.DocumentFormat
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.Paragraph
import kr.geulbeot.hwp.model.TextSpan
import java.nio.charset.Charset

/**
 * Plain text import and export.
 *
 * Korean plain text files are still frequently EUC-KR rather than UTF-8, so the decoder sniffs
 * rather than assuming: opening a legacy `.txt` as UTF-8 produces a screen of replacement
 * characters, which looks like a broken app rather than a wrong guess.
 */
object PlainTextCodec {

    private val UTF8 = Charsets.UTF_8
    private val EUC_KR: Charset? = runCatching { Charset.forName("EUC-KR") }.getOrNull()

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, UTF8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (isValidUtf8(bytes)) return String(bytes, UTF8)
        return EUC_KR?.let { String(bytes, it) } ?: String(bytes, UTF8)
    }

    fun encode(text: String): ByteArray = text.toByteArray(UTF8)

    fun read(bytes: ByteArray): HwpDocument {
        val document = HwpDocument.blank()
        document.format = DocumentFormat.PLAIN_TEXT
        val section = document.sections[0]
        val structural = section.paragraphs.firstOrNull()?.items?.toList().orEmpty()
        section.paragraphs.clear()
        val lines = decode(bytes).replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (line in lines) {
            val paragraph = Paragraph(paraShapeId = 0, styleId = 0)
            if (line.isEmpty()) paragraph.items.add(TextSpan("", 0)) else paragraph.appendText(line, 0)
            paragraph.dirty = true
            section.paragraphs.add(paragraph)
        }
        if (section.paragraphs.isEmpty()) {
            section.paragraphs.add(Paragraph().apply { items.add(TextSpan("", 0)) })
        }
        // Keep whatever the blank template anchored on its first paragraph.
        section.paragraphs[0].items.addAll(0, structural.filterIsInstance<kr.geulbeot.hwp.model.ControlSpan>())
        section.dirty = true
        return document
    }

    fun write(document: HwpDocument): ByteArray = encode(document.plainText())

    /**
     * Whether the bytes decode as UTF-8 without error. Written by hand rather than with a
     * CharsetDecoder so the answer does not depend on the platform's replacement behaviour.
     */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val extra = when {
                b <= 0x7F -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + extra >= bytes.size) return false
            for (k in 1..extra) {
                val c = bytes[i + k].toInt() and 0xFF
                if (c !in 0x80..0xBF) return false
            }
            i += extra + 1
        }
        return true
    }
}
