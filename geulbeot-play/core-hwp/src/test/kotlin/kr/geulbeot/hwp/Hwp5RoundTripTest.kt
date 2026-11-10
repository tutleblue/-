package kr.geulbeot.hwp

import kr.geulbeot.hwp.hwp5.BodyTextCodec
import kr.geulbeot.hwp.hwp5.CtrlId
import kr.geulbeot.hwp.hwp5.FileHeader
import kr.geulbeot.hwp.hwp5.Hwp5Reader
import kr.geulbeot.hwp.hwp5.Hwp5Writer
import kr.geulbeot.hwp.hwp5.HwpTag
import kr.geulbeot.hwp.model.CharControlSpan
import kr.geulbeot.hwp.model.CharShape
import kr.geulbeot.hwp.model.ControlSpan
import kr.geulbeot.hwp.model.HwpChar
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.LineSpacingKind
import kr.geulbeot.hwp.model.ParaAlign
import kr.geulbeot.hwp.model.ParaShape
import kr.geulbeot.hwp.model.Paragraph
import kr.geulbeot.hwp.model.RestrictedDocumentException
import kr.geulbeot.hwp.model.TextSpan
import kr.geulbeot.hwp.model.UnderlineKind
import kr.geulbeot.hwp.record.HwpRecord
import kr.geulbeot.hwp.record.HwpRecordCodec
import kr.geulbeot.hwp.util.HwpUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecordCodecTest {

    @Test
    fun `a record forest serialises back to exactly the bytes it was parsed from`() {
        val root = HwpRecord(HwpTag.PARA_HEADER, 0, ByteArray(22) { it.toByte() })
        root.children.add(HwpRecord(HwpTag.PARA_TEXT, 1, ByteArray(40) { (it * 3).toByte() }))
        val ctrl = HwpRecord(HwpTag.CTRL_HEADER, 1, ByteArray(30) { 7 })
        ctrl.children.add(HwpRecord(HwpTag.TABLE, 2, ByteArray(24) { 9 }))
        root.children.add(ctrl)
        val second = HwpRecord(HwpTag.PARA_HEADER, 0, ByteArray(22) { 1 })

        val bytes = HwpRecordCodec.serialize(listOf(root, second))
        val parsed = HwpRecordCodec.parse(bytes)
        assertContentEquals(bytes, HwpRecordCodec.serialize(parsed))

        assertEquals(2, parsed.size)
        assertEquals(2, parsed[0].children.size)
        assertEquals(1, parsed[0].children[1].children.size)
        assertEquals(HwpTag.TABLE, parsed[0].children[1].children[0].tagId)
    }

    @Test
    fun `a payload past the inline size limit uses the extended header and still round trips`() {
        // 0xFFF is the escape value, so anything that size or larger needs the four-byte form.
        val big = ByteArray(0x1234) { Random(it).nextInt(256).toByte() }
        val record = HwpRecord(HwpTag.PARA_TEXT, 0, big)
        val bytes = HwpRecordCodec.serialize(listOf(record))
        val parsed = HwpRecordCodec.parse(bytes)
        assertEquals(1, parsed.size)
        assertContentEquals(big, parsed[0].payload)
        assertContentEquals(bytes, HwpRecordCodec.serialize(parsed))
    }

    @Test
    fun `a source file that spells out a small size is re-emitted the same way`() {
        // Some producers use the extended form even when it is not required. Losing that would make
        // an untouched document differ byte for byte from the file it was opened from.
        val record = HwpRecord(HwpTag.PARA_TEXT, 0, ByteArray(10))
        record.forcedExtendedSize = true
        val bytes = HwpRecordCodec.serialize(listOf(record))
        assertEquals(4 + 4 + 10, bytes.size)
        val parsed = HwpRecordCodec.parse(bytes)
        assertTrue(parsed[0].forcedExtendedSize)
        assertContentEquals(bytes, HwpRecordCodec.serialize(parsed))
    }
}

class ParagraphTextTest {

    @Test
    fun `control characters advance the right number of code units`() {
        val paragraph = Paragraph(paraShapeId = 0, styleId = 0)
        paragraph.items.add(TextSpan("측정값은", 0))
        paragraph.items.add(ControlSpan(HwpChar.TAB, Paragraph.tabRawUnits(), charShapeId = 0))
        paragraph.items.add(TextSpan("412.6 N", 1))
        paragraph.items.add(CharControlSpan(HwpChar.LINE_BREAK, 1))
        paragraph.items.add(TextSpan("이다.", 1))

        // 4 + 8 + 7 + 1 + 3
        assertEquals(23, paragraph.wcharLength)
        assertEquals("측정값은\t412.6 N\n이다.", paragraph.text)

        val raw = BodyTextCodec.buildRawText(paragraph)
        assertEquals(23, raw.length, "raw 텍스트 길이가 WCHAR 개수와 일치해야 합니다")
    }

    @Test
    fun `an eight unit control does not shift the formatting that follows it`() {
        // This is the failure mode worth guarding: if a tab were counted as one unit instead of
        // eight, every character shape after it would be read from the wrong position.
        val document = documentWithMixedContent()
        val reread = Hwp5Reader.read(Hwp5Writer.write(document))
        val paragraph = reread.sections[0].paragraphs[1]

        val spans = paragraph.items.filterIsInstance<TextSpan>()
        assertEquals("측정값은", spans[0].text)
        assertEquals("412.6 N", spans[1].text)
        assertTrue(spans[0].charShapeId != spans[1].charShapeId, "탭 뒤의 글자 모양이 달라야 합니다")
        assertEquals(23, paragraph.wcharLength)
    }
}

class Hwp5DocumentRoundTripTest {

    @Test
    fun `text, fonts and formatting survive a write then read cycle`() {
        val original = documentWithMixedContent()
        val bytes = Hwp5Writer.write(original)

        assertTrue(Hwp5Reader.looksLikeHwp5(bytes), "쓴 파일이 한글 5.0 문서로 인식되어야 합니다")

        val reread = Hwp5Reader.read(bytes)
        assertEquals(original.plainText(), reread.plainText())
        assertEquals(original.sections.size, reread.sections.size)
        assertEquals(
            original.sections[0].paragraphs.size,
            reread.sections[0].paragraphs.size,
        )

        // Fonts: all seven script tables must come back with the same names in the same order.
        for (language in original.fontTables.indices) {
            assertEquals(
                original.fontTables[language].map { it.name },
                reread.fontTables[language].map { it.name },
                "글꼴 테이블 $language 가 달라졌습니다",
            )
        }

        // Character shapes.
        assertEquals(original.charShapes.size, reread.charShapes.size)
        for (i in original.charShapes.indices) {
            assertEquals(
                original.charShapes[i].signature(),
                reread.charShapes[i].signature(),
                "글자 모양 $i 이(가) 달라졌습니다",
            )
        }

        // Paragraph shapes.
        assertEquals(original.paraShapes.size, reread.paraShapes.size)
        for (i in original.paraShapes.indices) {
            assertEquals(
                original.paraShapes[i].signature(),
                reread.paraShapes[i].signature(),
                "문단 모양 $i 이(가) 달라졌습니다",
            )
        }

        assertEquals(original.styles.map { it.name }, reread.styles.map { it.name })
    }

    @Test
    fun `bold, italic, underline, colour and size are all preserved`() {
        val document = HwpDocument.blank()
        val font = document.ensureFont("함초롬돋움")
        val fancy = CharShape().apply {
            setFontForAllLanguages(font)
            sizePt = 14.5
            bold = true
            italic = true
            underline = UnderlineKind.BOTTOM
            textColor = 0x0000FF   // COLORREF: red channel in the low byte, so this is pure red
            shadeColor = 0x00FFFF
            superscript = true
        }
        val shapeId = document.ensureCharShape(fancy)
        document.sections[0].paragraphs[0].setPlainText("서식 검사", shapeId)

        val reread = Hwp5Reader.read(Hwp5Writer.write(document))
        val restored = reread.charShapes[shapeId]
        assertEquals(14.5, restored.sizePt, 0.001)
        assertTrue(restored.bold)
        assertTrue(restored.italic)
        assertEquals(UnderlineKind.BOTTOM, restored.underline)
        assertEquals(0x0000FF, restored.textColor)
        assertEquals(0x00FFFF, restored.shadeColor)
        assertTrue(restored.superscript)
        assertEquals("함초롬돋움", reread.fontName(restored.fontIds[0]))
    }

    @Test
    fun `paragraph alignment, indents and line spacing are preserved`() {
        val document = HwpDocument.blank()
        val shape = ParaShape(
            align = ParaAlign.CENTER,
            lineSpacingKind = LineSpacingKind.PERCENT,
            lineSpacing = 200,
            marginLeft = HwpUnit.fromMm(10.0),
            marginRight = HwpUnit.fromMm(5.0),
            indent = HwpUnit.fromPoint(-20.0),
            spaceBefore = HwpUnit.fromPoint(6.0),
            spaceAfter = HwpUnit.fromPoint(3.0),
        )
        val id = document.ensureParaShape(shape)
        document.sections[0].paragraphs[0].paraShapeId = id
        document.sections[0].paragraphs[0].setPlainText("가운데 정렬 문단")

        val reread = Hwp5Reader.read(Hwp5Writer.write(document))
        val restored = reread.paraShapes[id]
        assertEquals(ParaAlign.CENTER, restored.align)
        assertEquals(200, restored.lineSpacing)
        assertEquals(LineSpacingKind.PERCENT, restored.lineSpacingKind)
        assertEquals(HwpUnit.fromMm(10.0), restored.marginLeft)
        assertEquals(HwpUnit.fromMm(5.0), restored.marginRight)
        assertEquals(HwpUnit.fromPoint(-20.0), restored.indent)
        assertEquals(HwpUnit.fromPoint(6.0), restored.spaceBefore)
        assertEquals(HwpUnit.fromPoint(3.0), restored.spaceAfter)
    }

    @Test
    fun `page setup is written into the section definition and read back`() {
        val document = HwpDocument.blank()
        document.sections[0].pageDef = document.sections[0].pageDef.copy(
            width = HwpUnit.B5_WIDTH,
            height = HwpUnit.B5_HEIGHT,
            marginLeft = HwpUnit.fromMm(25.0),
            marginTop = HwpUnit.fromMm(18.0),
            landscape = true,
        )
        document.sections[0].dirty = true

        val reread = Hwp5Reader.read(Hwp5Writer.write(document))
        val page = reread.sections[0].pageDef
        assertEquals(HwpUnit.B5_WIDTH, page.width)
        assertEquals(HwpUnit.B5_HEIGHT, page.height)
        assertEquals(HwpUnit.fromMm(25.0), page.marginLeft)
        assertEquals(HwpUnit.fromMm(18.0), page.marginTop)
        assertTrue(page.landscape)
    }

    @Test
    fun `document metadata is written only when the user supplied some`() {
        val plain = Hwp5Writer.write(HwpDocument.blank())
        assertEquals("", Hwp5Reader.read(plain).summary.author, "빈 문서에 작성자가 임의로 들어가면 안 됩니다")

        val document = HwpDocument.blank()
        document.summary.title = "측정 결과와 오차 분석"
        document.summary.author = "김진영"
        document.summary.keywords = "인장강도, 표준편차"
        val reread = Hwp5Reader.read(Hwp5Writer.write(document))
        assertEquals("측정 결과와 오차 분석", reread.summary.title)
        assertEquals("김진영", reread.summary.author)
        assertEquals("인장강도, 표준편차", reread.summary.keywords)
    }

    @Test
    fun `an untouched paragraph is written back from its original records`() {
        // The preservation policy: only what the user edited gets regenerated.
        val first = Hwp5Writer.write(documentWithMixedContent())
        val opened = Hwp5Reader.read(first)

        val untouched = opened.sections[0].paragraphs[1]
        val sourceBefore = HwpRecordCodec.serialize(listOf(assertNotNull(untouched.sourceRecord)))

        opened.sections[0].paragraphs[0].setPlainText("제목을 고쳤습니다")
        val second = Hwp5Reader.read(Hwp5Writer.write(opened))

        val sourceAfter = HwpRecordCodec.serialize(listOf(assertNotNull(second.sections[0].paragraphs[1].sourceRecord)))
        assertContentEquals(sourceBefore, sourceAfter, "고치지 않은 문단은 원본 레코드 그대로 저장되어야 합니다")
        // The section definition anchored in the first paragraph survives its text being replaced,
        // and draws nothing, so the paragraph reads as plain text.
        assertEquals("제목을 고쳤습니다", second.sections[0].paragraphs[0].text)
        assertTrue(second.sections[0].paragraphs[0].controls().any { it.ctrlId == CtrlId.SECTION_DEF })
    }

    @Test
    fun `an uncompressed document is readable too`() {
        val document = documentWithMixedContent()
        document.compressed = false
        val bytes = Hwp5Writer.write(document)
        val reread = Hwp5Reader.read(bytes)
        assertEquals(document.plainText(), reread.plainText())
        assertTrue(!reread.compressed)
    }

    @Test
    fun `a password protected document is refused with an explanation rather than a parse error`() {
        val document = documentWithMixedContent()
        val bytes = Hwp5Writer.write(document)
        // Flip the password bit in the FileHeader stream, which is stored uncompressed.
        val tampered = withPasswordBitSet(bytes)
        val error = assertFailsWith<RestrictedDocumentException> { Hwp5Reader.read(tampered) }
        assertTrue(error.message!!.contains("암호"), "사용자가 무엇을 해야 할지 알 수 있어야 합니다")
    }

    @Test
    fun `the preview text stream carries the document text for fast listing`() {
        val document = documentWithMixedContent()
        val preview = Hwp5Reader.readPreviewText(Hwp5Writer.write(document))
        assertNotNull(preview)
        assertTrue(preview.contains("측정"), "미리보기 텍스트에 본문이 들어 있어야 합니다: $preview")
    }

    @Test
    fun `an empty new document still writes a section definition with page setup`() {
        val bytes = Hwp5Writer.write(HwpDocument.blank())
        val reread = Hwp5Reader.read(bytes)
        val controls = reread.sections[0].paragraphs[0].controls()
        assertTrue(
            controls.any { it.ctrlId == CtrlId.SECTION_DEF },
            "새 문서에도 구역 정의가 있어야 한글이 쪽 설정을 알 수 있습니다",
        )
        assertEquals(HwpUnit.A4_WIDTH, reread.sections[0].pageDef.width)
    }
}

// ---- shared fixtures -------------------------------------------------------------------------

internal fun documentWithMixedContent(): HwpDocument {
    val document = HwpDocument.blank()

    val headingFont = document.ensureFont("함초롬돋움")
    val headingChar = document.ensureCharShape(
        CharShape().apply {
            setFontForAllLanguages(headingFont)
            sizePt = 16.0
            bold = true
        },
    )
    val headingPara = document.ensureParaShape(
        ParaShape(align = ParaAlign.CENTER, lineSpacing = 130, spaceAfter = HwpUnit.fromPoint(8.0)),
    )
    val emphasisChar = document.ensureCharShape(
        CharShape().apply {
            setFontForAllLanguages(document.ensureFont(HwpDocument.DEFAULT_BODY_FONT))
            sizePt = 10.0
            bold = true
            textColor = 0x0000C0
        },
    )

    val section = document.sections[0]
    section.paragraphs.clear()

    section.paragraphs.add(
        Paragraph(paraShapeId = headingPara, styleId = 0).apply {
            items.add(TextSpan("제3장 측정 결과와 오차 분석", headingChar))
            dirty = true
        },
    )
    section.paragraphs.add(
        Paragraph(paraShapeId = 0, styleId = 0).apply {
            items.add(TextSpan("측정값은", 0))
            items.add(ControlSpan(HwpChar.TAB, Paragraph.tabRawUnits(), charShapeId = 0))
            items.add(TextSpan("412.6 N", emphasisChar))
            items.add(CharControlSpan(HwpChar.LINE_BREAK, emphasisChar))
            items.add(TextSpan("이다.", 0))
            dirty = true
        },
    )
    section.paragraphs.add(
        Paragraph(paraShapeId = 0, styleId = 0).apply {
            setPlainText("시료 A와 시료 B의 인장강도를 각각 5회 측정하여 평균과 표준편차를 구하였다.", 0)
        },
    )
    section.dirty = true
    return document
}

private fun withPasswordBitSet(bytes: ByteArray): ByteArray {
    val cfb = kr.geulbeot.hwp.cfb.CfbReader(bytes)
    val header = cfb.read(Hwp5Reader.STREAM_FILE_HEADER).copyOf()
    header[36] = (header[36].toInt() or FileHeader.BIT_PASSWORD).toByte()
    val writer = kr.geulbeot.hwp.cfb.CfbWriter()
    for (name in cfb.streamNames()) {
        writer.put(name, if (name == Hwp5Reader.STREAM_FILE_HEADER) header else cfb.read(name))
    }
    return writer.build()
}
