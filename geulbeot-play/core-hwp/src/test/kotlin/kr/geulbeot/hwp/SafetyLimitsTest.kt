package kr.geulbeot.hwp

import kr.geulbeot.hwp.hwpx.HwpxReader
import kr.geulbeot.hwp.util.Compression
import kr.geulbeot.hwp.util.HwpFormatException
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A document is untrusted input. It can arrive from a messenger, a download, or a shared link, and
 * opening one must not be able to take the app down.
 */
class SafetyLimitsTest {

    @Test
    fun `a stream that expands far beyond its size is refused rather than exhausting memory`() {
        // Four megabytes of zeros compresses to a few kilobytes; ask for it back under a small cap.
        val bomb = Compression.deflateRaw(ByteArray(4 * 1024 * 1024))
        assertTrue(bomb.size < 64 * 1024, "테스트 전제: 압축된 크기가 작아야 합니다 (${bomb.size} bytes)")

        val error = assertFailsWith<HwpFormatException> {
            Compression.inflateRaw(bomb, limit = 64 * 1024)
        }
        assertTrue(error.message!!.contains("비정상적으로"), "사용자가 이해할 수 있는 메시지여야 합니다")

        // Under the real ceiling the same stream is perfectly fine.
        assertEquals(4 * 1024 * 1024, Compression.inflateRaw(bomb).size)
    }

    @Test
    fun `archive entries that would escape the folder are ignored`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in listOf("../escape.xml", "/absolute.xml", "Contents/header.xml")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("<hh:head/>".toByteArray())
                zip.closeEntry()
            }
        }
        val entries = HwpxReader.readZip(out.toByteArray())
        assertEquals(setOf("Contents/header.xml"), entries.keys)
    }

    @Test
    fun `an XML document cannot pull in an external entity`() {
        // An external entity would let a document read a file off the device or call out to a
        // network host the moment it is opened.
        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE root [ <!ENTITY secret SYSTEM "file:///etc/passwd"> ]>
            <root>&secret;</root>
        """.trimIndent().toByteArray()

        // Either the parser refuses the doctype outright or it resolves the entity to nothing.
        // Both are acceptable; silently inlining the file is not.
        val text = runCatching { kr.geulbeot.hwp.xml.XmlNode.parse(hostile).deepText() }.getOrDefault("")
        assertTrue(!text.contains("root:"), "외부 엔티티가 확장되면 안 됩니다: $text")
    }
}
