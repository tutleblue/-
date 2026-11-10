package kr.geulbeot.hwp

import kr.geulbeot.hwp.cfb.CfbReader
import kr.geulbeot.hwp.cfb.CfbWriter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CfbRoundTripTest {

    private fun pattern(size: Int, seed: Int): ByteArray {
        val r = Random(seed)
        return ByteArray(size) { r.nextInt(256).toByte() }
    }

    @Test
    fun `streams survive a write then read cycle at every size boundary`() {
        // The interesting sizes are the ones either side of the 64-byte mini sector, the 4096-byte
        // mini stream cutoff and the 512-byte sector.
        val cases = linkedMapOf(
            "FileHeader" to pattern(256, 1),
            "DocInfo" to pattern(4095, 2),          // last size that still lives in the mini stream
            "BodyText/Section0" to pattern(4096, 3), // first size that needs full sectors
            "BodyText/Section1" to pattern(512, 4),
            "BodyText/Section2" to pattern(64, 5),
            "BinData/BIN0001.png" to pattern(200_000, 6),
            "PrvText" to pattern(1, 7),
            "Empty" to ByteArray(0),
            "Scripts/DefaultJScript" to pattern(63, 8),
        )

        val bytes = CfbWriter().putAll(cases).build()
        assertEquals(0, bytes.size % 512, "파일 크기는 섹터 크기의 배수여야 합니다")

        val reader = CfbReader(bytes)
        assertEquals(cases.keys.sorted(), reader.streamNames().sorted())
        for ((path, expected) in cases) {
            assertContentEquals(expected, reader.read(path), "스트림 '$path' 내용이 달라졌습니다")
        }
    }

    @Test
    fun `a document large enough to need DIFAT sectors still round trips`() {
        // 109 header DIFAT slots cover roughly 7 MiB; go past that so the chained DIFAT path runs.
        val big = pattern(9 * 1024 * 1024, 42)
        val bytes = CfbWriter().put("FileHeader", pattern(256, 1)).put("BinData/BIN0001.bmp", big).build()
        val reader = CfbReader(bytes)
        assertContentEquals(big, reader.read("BinData/BIN0001.bmp"))
        assertContentEquals(pattern(256, 1), reader.read("FileHeader"))
    }

    @Test
    fun `nested storages keep their paths`() {
        val bytes = CfbWriter()
            .put("BodyText/Section0", pattern(100, 1))
            .put("BinData/BIN0001.png", pattern(100, 2))
            .put("BinData/BIN0002.png", pattern(100, 3))
            .put("FileHeader", pattern(256, 4))
            .build()
        val reader = CfbReader(bytes)
        assertEquals(listOf("BIN0001.png", "BIN0002.png"), reader.childrenOf("BinData").sorted())
        assertEquals(listOf("Section0"), reader.childrenOf("BodyText"))
        assertTrue(reader.hasStream("FileHeader"))
    }

    @Test
    fun `many streams force multiple directory sectors`() {
        val w = CfbWriter()
        val expected = linkedMapOf<String, ByteArray>()
        for (i in 0 until 60) {
            val name = "BodyText/Section$i"
            val data = pattern(100 + i, i)
            expected[name] = data
            w.put(name, data)
        }
        val reader = CfbReader(w.build())
        for ((path, data) in expected) assertContentEquals(data, reader.read(path))
    }

    @Test
    fun `a file that is not a compound document is rejected with a readable message`() {
        val e = assertFailsWith<kr.geulbeot.hwp.util.HwpFormatException> {
            CfbReader("이건 그냥 텍스트 파일입니다".toByteArray(Charsets.UTF_8) + ByteArray(1000))
        }
        assertTrue(e.message!!.contains("한글 문서"), "오류 메시지가 사용자에게 이해되는 말이어야 합니다: ${e.message}")
    }
}
