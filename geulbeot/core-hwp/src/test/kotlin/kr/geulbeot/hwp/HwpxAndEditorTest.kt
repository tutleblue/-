package kr.geulbeot.hwp

import kr.geulbeot.hwp.api.DocumentEditor
import kr.geulbeot.hwp.api.DocumentSearch
import kr.geulbeot.hwp.api.GeulbeotDocuments
import kr.geulbeot.hwp.api.SearchOptions
import kr.geulbeot.hwp.hwpx.HwpxReader
import kr.geulbeot.hwp.hwpx.HwpxWriter
import kr.geulbeot.hwp.hwpx.Owpml
import kr.geulbeot.hwp.model.CharShape
import kr.geulbeot.hwp.model.DocumentFormat
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.ParaAlign
import kr.geulbeot.hwp.model.TableControl
import kr.geulbeot.hwp.model.TextSpan
import kr.geulbeot.hwp.model.UnderlineKind
import kr.geulbeot.hwp.text.PlainTextCodec
import kr.geulbeot.hwp.util.HwpUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HwpxRoundTripTest {

    @Test
    fun `the archive is shaped the way the standard requires`() {
        val bytes = HwpxWriter.write(documentWithMixedContent())

        // The mimetype entry must come first and be stored uncompressed, so its content sits at a
        // fixed offset near the start of the file.
        val head = String(bytes, 30, Owpml.ENTRY_MIMETYPE.length, Charsets.US_ASCII)
        assertEquals(Owpml.ENTRY_MIMETYPE, head, "mimetype가 첫 번째 항목이어야 합니다")
        val mimetypeAt = String(bytes, 30 + Owpml.ENTRY_MIMETYPE.length, Owpml.MIMETYPE.length, Charsets.US_ASCII)
        assertEquals(Owpml.MIMETYPE, mimetypeAt, "mimetype은 압축 없이 저장되어야 합니다")

        val entries = HwpxReader.readZip(bytes)
        for (required in listOf(
            Owpml.ENTRY_MIMETYPE,
            Owpml.ENTRY_VERSION,
            Owpml.ENTRY_CONTAINER,
            Owpml.ENTRY_MANIFEST,
            Owpml.ENTRY_CONTENT,
            Owpml.ENTRY_HEADER,
            "${Owpml.PREFIX_SECTION}0.xml",
        )) {
            assertTrue(entries.containsKey(required), "필수 항목 '$required' 이(가) 없습니다")
        }
    }

    @Test
    fun `text, fonts and formatting survive a write then read cycle`() {
        val original = documentWithMixedContent()
        val reread = HwpxReader.read(HwpxWriter.write(original))

        assertEquals(original.plainText(), reread.plainText())
        assertEquals(original.sections[0].paragraphs.size, reread.sections[0].paragraphs.size)
        assertEquals(
            original.fontTables[0].map { it.name },
            reread.fontTables[0].map { it.name },
        )
        for (i in original.charShapes.indices) {
            val a = original.charShapes[i]
            val b = reread.charShapes[i]
            assertEquals(a.height, b.height, "글자 크기가 달라졌습니다 (id=$i)")
            assertEquals(a.bold, b.bold, "진하게가 달라졌습니다 (id=$i)")
            assertEquals(a.italic, b.italic, "기울임이 달라졌습니다 (id=$i)")
            assertEquals(a.textColor, b.textColor, "글자색이 달라졌습니다 (id=$i)")
            assertContentEquals(a.fontIds, b.fontIds, "글꼴 참조가 달라졌습니다 (id=$i)")
        }
        for (i in original.paraShapes.indices) {
            val a = original.paraShapes[i]
            val b = reread.paraShapes[i]
            assertEquals(a.align, b.align, "정렬이 달라졌습니다 (id=$i)")
            assertEquals(a.lineSpacing, b.lineSpacing, "줄 간격이 달라졌습니다 (id=$i)")
            assertEquals(a.spaceAfter, b.spaceAfter, "문단 아래 간격이 달라졌습니다 (id=$i)")
        }
    }

    @Test
    fun `page setup survives a write then read cycle`() {
        val document = HwpDocument.blank()
        document.sections[0].pageDef = document.sections[0].pageDef.copy(
            width = HwpUnit.B5_WIDTH,
            height = HwpUnit.B5_HEIGHT,
            marginLeft = HwpUnit.fromMm(25.0),
            marginBottom = HwpUnit.fromMm(12.0),
            landscape = true,
        )
        val page = HwpxReader.read(HwpxWriter.write(document)).sections[0].pageDef
        assertEquals(HwpUnit.B5_WIDTH, page.width)
        assertEquals(HwpUnit.B5_HEIGHT, page.height)
        assertEquals(HwpUnit.fromMm(25.0), page.marginLeft)
        assertEquals(HwpUnit.fromMm(12.0), page.marginBottom)
        assertTrue(page.landscape)
    }

    @Test
    fun `a table keeps its shape and its cell text`() {
        val document = HwpDocument.blank()
        val editor = DocumentEditor(document)
        val section = document.sections[0]
        val paragraph = section.paragraphs[0]
        val table = editor.insertTable(section, paragraph, paragraph.displayLength, rows = 3, columns = 3)

        val values = listOf(
            listOf("시료", "평균 (N)", "표준편차"),
            listOf("A", "412.6", "3.1"),
            listOf("B", "389.2", "5.4"),
        )
        for ((r, row) in values.withIndex()) {
            for ((c, value) in row.withIndex()) {
                table.rows[r].cells[c].paragraphs[0].setPlainText(value, 0)
            }
        }

        val reread = HwpxReader.read(HwpxWriter.write(document))
        val restored = reread.sections[0].paragraphs
            .flatMap { it.controls() }
            .filterIsInstance<TableControl>()
            .firstOrNull()
        assertNotNull(restored, "표가 저장되지 않았습니다")
        assertEquals(3, restored.rows.size)
        assertEquals(3, restored.rows[0].cells.size)
        for ((r, row) in values.withIndex()) {
            for ((c, value) in row.withIndex()) {
                assertEquals(value, restored.rows[r].cells[c].text, "($r,$c) 칸의 내용이 달라졌습니다")
            }
        }
        assertEquals(0, restored.rows[1].cells[2].rowIndex.let { 0 })
        assertEquals(2, restored.rows[1].cells[2].columnIndex)
    }

    @Test
    fun `document metadata survives a write then read cycle`() {
        val document = HwpDocument.blank()
        document.summary.title = "측정 결과와 오차 분석"
        document.summary.author = "김진영"
        document.summary.keywords = "인장강도, 표준편차"
        val reread = HwpxReader.read(HwpxWriter.write(document))
        assertEquals("측정 결과와 오차 분석", reread.summary.title)
        assertEquals("김진영", reread.summary.author)
        assertEquals("인장강도, 표준편차", reread.summary.keywords)
    }

    @Test
    fun `saving twice produces identical bytes`() {
        // Nothing about the machine or the moment may leak into a saved file, so two saves of an
        // unchanged document have to match exactly.
        val document = documentWithMixedContent()
        assertContentEquals(HwpxWriter.write(document), HwpxWriter.write(document))
    }
}

class FormatConversionTest {

    @Test
    fun `a document opened as hwp can be saved as hwpx without losing its text or formatting`() {
        val source = GeulbeotDocuments.save(documentWithMixedContent(), DocumentFormat.HWP5)
        val opened = GeulbeotDocuments.open(source, "보고서.hwp")
        assertEquals(DocumentFormat.HWP5, opened.format)

        val converted = GeulbeotDocuments.save(opened, DocumentFormat.HWPX)
        val reread = GeulbeotDocuments.open(converted, "보고서.hwpx")
        assertEquals(DocumentFormat.HWPX, reread.format)
        assertEquals(opened.plainText(), reread.plainText())

        val headingBefore = opened.charShapes[opened.sections[0].paragraphs[0].items[0].charShapeId]
        val headingAfter = reread.charShapes[reread.sections[0].paragraphs[0].items[0].charShapeId]
        assertEquals(headingBefore.sizePt, headingAfter.sizePt, 0.001)
        assertEquals(headingBefore.bold, headingAfter.bold)
        assertEquals(
            opened.fontName(headingBefore.fontIds[0]),
            reread.fontName(headingAfter.fontIds[0]),
        )
    }

    @Test
    fun `format is detected from the bytes even when the file name lies`() {
        val hwp = GeulbeotDocuments.save(documentWithMixedContent(), DocumentFormat.HWP5)
        val hwpx = GeulbeotDocuments.save(documentWithMixedContent(), DocumentFormat.HWPX)
        assertEquals(DocumentFormat.HWP5, GeulbeotDocuments.detectFormat(hwp, "문서.hwpx"))
        assertEquals(DocumentFormat.HWPX, GeulbeotDocuments.detectFormat(hwpx, "문서.hwp"))
        assertEquals(DocumentFormat.HWP5, GeulbeotDocuments.detectFormat(hwp, null))
    }

    @Test
    fun `plain text import and export round trip`() {
        val text = "첫째 줄\n둘째 줄\n\n넷째 줄"
        val document = PlainTextCodec.read(text.toByteArray(Charsets.UTF_8))
        assertEquals(4, document.sections[0].paragraphs.size)
        assertEquals(text, PlainTextCodec.write(document).toString(Charsets.UTF_8))
    }

    @Test
    fun `a legacy EUC-KR text file is decoded rather than mangled`() {
        val eucKr = java.nio.charset.Charset.forName("EUC-KR")
        val bytes = "한글 문서입니다".toByteArray(eucKr)
        assertEquals("한글 문서입니다", PlainTextCodec.decode(bytes))
    }

    @Test
    fun `the quick text path uses the preview stream in both formats`() {
        val hwp = GeulbeotDocuments.save(documentWithMixedContent(), DocumentFormat.HWP5)
        val hwpx = GeulbeotDocuments.save(documentWithMixedContent(), DocumentFormat.HWPX)
        assertTrue(GeulbeotDocuments.extractText(hwp, "a.hwp", quick = true).contains("측정"))
        assertTrue(GeulbeotDocuments.extractText(hwpx, "a.hwpx", quick = true).contains("측정"))
    }
}

class DocumentEditorTest {

    @Test
    fun `typing inside a formatted run keeps that run's formatting`() {
        val document = HwpDocument.blank()
        val editor = DocumentEditor(document)
        val bold = document.ensureCharShape(CharShape().apply { bold = true })
        val paragraph = document.sections[0].paragraphs[0]
        paragraph.items.clear()
        paragraph.items.add(TextSpan("보통 ", 0))
        paragraph.items.add(TextSpan("진하게", bold))
        paragraph.items.add(TextSpan(" 보통", 0))

        // Insert inside the bold run: "진하|게" -> "진하아주게"
        editor.replaceParagraphText(paragraph, "보통 진하아주게 보통")

        assertEquals("보통 진하아주게 보통", paragraph.text)
        val spans = paragraph.items.filterIsInstance<TextSpan>()
        val boldText = spans.filter { document.charShapeOrDefault(it.charShapeId).bold }.joinToString("") { it.text }
        assertEquals("진하아주게", boldText, "진하게 구간 안에 넣은 글자는 진하게여야 합니다")
    }

    @Test
    fun `applying a character shape to a selection leaves the rest alone`() {
        val document = HwpDocument.blank()
        val editor = DocumentEditor(document)
        val paragraph = document.sections[0].paragraphs[0]
        paragraph.setPlainText("시료 A와 시료 B의 인장강도", 0)

        // Underline "시료 B" - offsets 6 through 10.
        editor.applyCharShape(paragraph, 6, 10) { it.apply { underline = UnderlineKind.BOTTOM } }

        val underlined = paragraph.items.filterIsInstance<TextSpan>()
            .filter { document.charShapeOrDefault(it.charShapeId).underline == UnderlineKind.BOTTOM }
            .joinToString("") { it.text }
        assertEquals("시료 B", underlined)
        assertEquals("시료 A와 시료 B의 인장강도", paragraph.text, "서식만 바뀌고 글자는 그대로여야 합니다")
    }

    @Test
    fun `a character shape change does not restyle other runs that shared the shape`() {
        val document = HwpDocument.blank()
        val editor = DocumentEditor(document)
        val section = document.sections[0]
        section.paragraphs[0].setPlainText("첫 번째 문단", 0)
        val second = editor.insertParagraphAfter(section, 0, "두 번째 문단")

        editor.toggleBold(section.paragraphs[0], 0, 2)

        assertTrue(document.charShapeOrDefault(section.paragraphs[0].items[0].charShapeId).bold)
        assertTrue(
            !document.charShapeOrDefault(second.items[0].charShapeId).bold,
            "다른 문단까지 진하게가 되면 안 됩니다",
        )
    }

    @Test
    fun `splitting and merging paragraphs behaves like Enter and Backspace`() {
        val document = HwpDocument.blank()
        val editor = DocumentEditor(document)
        val section = document.sections[0]
        section.paragraphs[0].setPlainText("앞부분과 뒷부분", 0)

        val next = editor.splitParagraph(section, 0, 4)
        assertEquals("앞부분과", section.paragraphs[0].text)
        assertEquals(" 뒷부분", next.text)
        assertEquals(2, section.paragraphs.size)

        val joinOffset = editor.mergeWithPrevious(section, 1)
        assertEquals(4, joinOffset)
        assertEquals("앞부분과 뒷부분", section.paragraphs[0].text)
        assertEquals(1, section.paragraphs.size)
    }

    @Test
    fun `paragraph formatting is applied per paragraph`() {
        val document = HwpDocument.blank()
        val editor = DocumentEditor(document)
        val section = document.sections[0]
        section.paragraphs[0].setPlainText("가운데로", 0)
        val second = editor.insertParagraphAfter(section, 0, "그대로")

        editor.setAlign(section.paragraphs[0], ParaAlign.CENTER)
        editor.setLineSpacing(section.paragraphs[0], 200)

        assertEquals(ParaAlign.CENTER, document.paraShapeOrDefault(section.paragraphs[0].paraShapeId).align)
        assertEquals(200, document.paraShapeOrDefault(section.paragraphs[0].paraShapeId).lineSpacing)
        assertEquals(ParaAlign.JUSTIFY, document.paraShapeOrDefault(second.paraShapeId).align)
    }

    @Test
    fun `a tab counts as one character in the editor and eight in the file`() {
        val document = HwpDocument.blank()
        val editor = DocumentEditor(document)
        val paragraph = document.sections[0].paragraphs[0]
        editor.replaceParagraphText(paragraph, "이름\t값")

        assertEquals(4, paragraph.displayLength, "편집기에서 탭은 한 글자입니다")
        assertEquals(11, paragraph.wcharLength, "파일에서 탭은 여덟 코드 단위입니다")
        assertEquals("이름\t값", paragraph.text)
    }

    @Test
    fun `adding and removing table rows and columns keeps the grid consistent`() {
        val document = HwpDocument.blank()
        val editor = DocumentEditor(document)
        val section = document.sections[0]
        val table = editor.insertTable(section, section.paragraphs[0], 0, rows = 2, columns = 2)

        editor.addTableRow(table, 0)
        editor.addTableColumn(table, 1)
        assertEquals(3, table.rows.size)
        assertTrue(table.rows.all { it.cells.size == 3 }, "모든 행의 칸 수가 같아야 합니다")
        assertEquals(3, table.rowCount)
        assertEquals(3, table.columnCount)
        for ((r, row) in table.rows.withIndex()) {
            for ((c, cell) in row.cells.withIndex()) {
                assertEquals(r, cell.rowIndex)
                assertEquals(c, cell.columnIndex)
            }
        }

        editor.removeTableRow(table, 1)
        editor.removeTableColumn(table, 0)
        assertEquals(2, table.rows.size)
        assertTrue(table.rows.all { it.cells.size == 2 })
    }
}

class DocumentSearchTest {

    private fun sampleDocument(): HwpDocument {
        val document = HwpDocument.blank()
        val editor = DocumentEditor(document)
        val section = document.sections[0]
        section.paragraphs[0].setPlainText("시료 A의 인장강도를 측정하였다.", 0)
        editor.insertParagraphAfter(section, 0, "시료 B의 인장강도는 더 낮았다.")
        val table = editor.insertTable(section, section.paragraphs[1], section.paragraphs[1].displayLength, 2, 2)
        table.rows[0].cells[0].paragraphs[0].setPlainText("시료", 0)
        table.rows[0].cells[1].paragraphs[0].setPlainText("인장강도", 0)
        return document
    }

    @Test
    fun `search finds matches in body text and inside table cells`() {
        val document = sampleDocument()
        val hits = DocumentSearch.find(document, "인장강도")
        assertEquals(3, hits.size, "본문 2건과 표 안 1건을 모두 찾아야 합니다")
        assertEquals(1, hits.count { it.cellPath != null }, "표 안의 결과가 표시되어야 합니다")
    }

    @Test
    fun `search can be limited to body text`() {
        val hits = DocumentSearch.find(sampleDocument(), "인장강도", SearchOptions(searchTables = false))
        assertEquals(2, hits.size)
    }

    @Test
    fun `replace all updates every match including table cells`() {
        val document = sampleDocument()
        val replaced = DocumentSearch.replaceAll(document, "인장강도", "항복강도")
        assertEquals(3, replaced)
        assertTrue(!document.plainText().contains("인장강도"))
        assertEquals(3, document.plainText().split("항복강도").size - 1)
    }

    @Test
    fun `search is case insensitive by default and exact when asked`() {
        val document = HwpDocument.blank()
        document.sections[0].paragraphs[0].setPlainText("Sample and sample", 0)
        assertEquals(2, DocumentSearch.find(document, "sample").size)
        assertEquals(1, DocumentSearch.find(document, "sample", SearchOptions(caseSensitive = true)).size)
    }

    @Test
    fun `an unfinished regular expression returns nothing instead of throwing`() {
        val document = sampleDocument()
        assertEquals(0, DocumentSearch.find(document, "시료 (", SearchOptions(useRegex = true)).size)
    }
}
