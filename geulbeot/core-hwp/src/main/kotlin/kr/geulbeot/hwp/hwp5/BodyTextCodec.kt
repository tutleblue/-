package kr.geulbeot.hwp.hwp5

import kr.geulbeot.hwp.model.CharControlSpan
import kr.geulbeot.hwp.model.Control
import kr.geulbeot.hwp.model.ControlCharKind
import kr.geulbeot.hwp.model.ControlSpan
import kr.geulbeot.hwp.model.HeaderFooterControl
import kr.geulbeot.hwp.model.HwpChar
import kr.geulbeot.hwp.model.NoteControl
import kr.geulbeot.hwp.model.OpaqueControl
import kr.geulbeot.hwp.model.PageDef
import kr.geulbeot.hwp.model.ParaItem
import kr.geulbeot.hwp.model.Paragraph
import kr.geulbeot.hwp.model.PictureControl
import kr.geulbeot.hwp.model.Section
import kr.geulbeot.hwp.model.SectionDefControl
import kr.geulbeot.hwp.model.TableCell
import kr.geulbeot.hwp.model.TableControl
import kr.geulbeot.hwp.model.TableRow
import kr.geulbeot.hwp.model.TextSpan
import kr.geulbeot.hwp.record.HwpRecord
import kr.geulbeot.hwp.util.ByteReader
import kr.geulbeot.hwp.util.ByteWriter
import kr.geulbeot.hwp.util.decodeUtf16Le
import kr.geulbeot.hwp.util.encodeUtf16Le

/**
 * Reads and writes a `BodyText/SectionN` stream.
 *
 * Two details carry most of the weight here:
 *
 *  - Paragraph text is an array of UTF-16 code units in which control characters occupy one or
 *    eight slots. `PARA_CHAR_SHAPE` positions count those slots, so the walker in [readParagraph]
 *    has to advance by exactly the right amount or all formatting after the first table shifts.
 *  - Extended control characters do not carry their own data. The Nth extended control in the text
 *    refers to the Nth `CTRL_HEADER` record that follows it, which is why order is preserved rather
 *    than looked up.
 */
object BodyTextCodec {

    fun ctrlIdString(value: Int): String = String(
        charArrayOf(
            ((value ushr 24) and 0xFF).toChar(),
            ((value ushr 16) and 0xFF).toChar(),
            ((value ushr 8) and 0xFF).toChar(),
            (value and 0xFF).toChar(),
        ),
    )

    fun ctrlIdValue(id: String): Int {
        val s = id.padEnd(4, ' ')
        return (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code
    }

    // ---- reading ---------------------------------------------------------------------------

    fun readSection(records: List<HwpRecord>): Section {
        val section = Section()
        section.sourceRecords = records
        for (record in records) {
            if (record.tagId != HwpTag.PARA_HEADER) continue
            val paragraph = readParagraph(record)
            section.paragraphs.add(paragraph)
        }
        // Page setup lives on the section-definition control attached to the first paragraph.
        section.paragraphs.firstOrNull()
            ?.controls()
            ?.filterIsInstance<SectionDefControl>()
            ?.firstOrNull()
            ?.let { section.pageDef = it.pageDef }
        return section
    }

    fun readParagraph(record: HwpRecord): Paragraph {
        val header = record.reader()
        val declaredChars = header.i32() and 0x7FFF_FFFF
        val controlMask = if (header.remaining >= 4) header.u32() else 0L
        val paraShapeId = if (header.remaining >= 2) header.u16() else 0
        val styleId = if (header.remaining >= 1) header.u8() else 0
        val breakType = if (header.remaining >= 1) header.u8() else 0

        val paragraph = Paragraph(paraShapeId, styleId)
        paragraph.controlMask = controlMask
        paragraph.breakType = breakType
        paragraph.sourceRecord = record

        val rawText = record.firstChild(HwpTag.PARA_TEXT)?.let { decodeUtf16Le(it.payload) } ?: ""
        val charShapes = record.firstChild(HwpTag.PARA_CHAR_SHAPE)?.let { readCharShapeRuns(it) } ?: emptyList()
        val controlRecords = record.childrenWithTag(HwpTag.CTRL_HEADER)

        if (rawText.isEmpty()) {
            // An empty paragraph still carries a character shape, which is what new typing inherits.
            paragraph.items.add(TextSpan("", charShapes.firstOrNull()?.second ?: 0))
            if (declaredChars == 0 && controlRecords.isNotEmpty()) {
                attachOrphanControls(paragraph, controlRecords)
            }
            return paragraph
        }

        var index = 0
        var controlIndex = 0
        val pending = StringBuilder()
        var pendingStart = 0

        fun flushText() {
            if (pending.isEmpty()) return
            paragraph.items.add(TextSpan(pending.toString(), shapeAt(charShapes, pendingStart)))
            pending.setLength(0)
        }

        while (index < rawText.length) {
            val code = rawText[index].code
            when (HwpChar.kindOf(code)) {
                ControlCharKind.NONE -> {
                    if (pending.isEmpty()) pendingStart = index
                    // Split a text run wherever the character shape changes.
                    val shapeHere = shapeAt(charShapes, index)
                    if (pending.isNotEmpty() && shapeHere != shapeAt(charShapes, pendingStart)) {
                        flushText()
                        pendingStart = index
                    }
                    pending.append(rawText[index])
                    index++
                }
                ControlCharKind.CHAR -> {
                    flushText()
                    paragraph.items.add(CharControlSpan(code, shapeAt(charShapes, index)))
                    index++
                }
                ControlCharKind.INLINE -> {
                    flushText()
                    val chunk = slice(rawText, index, 8)
                    paragraph.items.add(ControlSpan(code, chunk, charShapeId = shapeAt(charShapes, index)))
                    index += 8
                }
                ControlCharKind.EXTENDED -> {
                    flushText()
                    val chunk = slice(rawText, index, 8)
                    // Frame: [control char][id low][id high][four info units][control char].
                    // The control id is a UINT32 split over two code units, low half first.
                    val idValue = if (chunk.length >= 3) (chunk[2].code shl 16) or chunk[1].code else 0
                    val ctrlId = ctrlIdString(idValue)
                    val ctrlRecord = controlRecords.getOrNull(controlIndex)
                    controlIndex++
                    val control = ctrlRecord?.let { parseControl(it, ctrlId) }
                    paragraph.items.add(
                        ControlSpan(code, chunk, ctrlId, control, shapeAt(charShapes, index)),
                    )
                    index += 8
                }
            }
        }
        flushText()
        if (paragraph.items.isEmpty()) paragraph.items.add(TextSpan("", 0))
        return paragraph
    }

    /** Controls on a paragraph with no text at all, which happens for section definitions. */
    private fun attachOrphanControls(paragraph: Paragraph, controlRecords: List<HwpRecord>) {
        for (record in controlRecords) {
            val idValue = ByteReader(record.payload).i32()
            val ctrlId = ctrlIdString(idValue)
            paragraph.items.add(
                ControlSpan(HwpChar.EXTENDED_OBJECT, "", ctrlId, parseControl(record, ctrlId), 0),
            )
        }
    }

    private fun slice(text: String, start: Int, length: Int): String =
        text.substring(start, minOf(start + length, text.length))

    private fun readCharShapeRuns(record: HwpRecord): List<Pair<Int, Int>> {
        val r = record.reader()
        val runs = ArrayList<Pair<Int, Int>>()
        while (r.remaining >= 8) {
            val position = r.i32()
            val shapeId = r.i32()
            runs.add(position to shapeId)
        }
        return runs
    }

    private fun shapeAt(runs: List<Pair<Int, Int>>, position: Int): Int {
        if (runs.isEmpty()) return 0
        var result = runs[0].second
        for ((start, id) in runs) {
            if (start > position) break
            result = id
        }
        return result
    }

    // ---- controls ---------------------------------------------------------------------------

    private fun parseControl(record: HwpRecord, ctrlId: String): Control {
        val control: Control = when (ctrlId) {
            CtrlId.TABLE -> parseTable(record)
            CtrlId.SECTION_DEF -> parseSectionDef(record)
            CtrlId.HEADER -> parseHeaderFooter(record, isHeader = true)
            CtrlId.FOOTER -> parseHeaderFooter(record, isHeader = false)
            CtrlId.FOOTNOTE -> parseNote(record, isFootnote = true)
            CtrlId.ENDNOTE -> parseNote(record, isFootnote = false)
            CtrlId.GENERAL_SHAPE -> parseShape(record)
            else -> OpaqueControl(ctrlId)
        }
        control.sourceRecord = record
        return control
    }

    private fun parseTable(record: HwpRecord): TableControl {
        val table = TableControl()
        val tableRecord = record.firstChild(HwpTag.TABLE)
        if (tableRecord != null) {
            val r = tableRecord.reader()
            r.i32()                       // attributes
            table.rowCount = r.u16()
            table.columnCount = r.u16()
            table.cellSpacing = r.i16()
            table.insideMarginLeft = r.i16()
            table.insideMarginRight = r.i16()
            table.insideMarginTop = r.i16()
            table.insideMarginBottom = r.i16()
            val cellsPerRow = IntArray(table.rowCount) { if (r.remaining >= 2) r.u16() else 0 }
            if (r.remaining >= 2) table.borderFillId = r.u16()
            for (count in cellsPerRow) {
                val row = TableRow()
                repeat(count) { row.cells.add(TableCell()) }
                table.rows.add(row)
            }
        }

        // Cells follow as LIST_HEADER records, laid out row by row.
        val cellHeaders = record.childrenWithTag(HwpTag.LIST_HEADER)
        val flatCells = table.rows.flatMap { it.cells }
        for ((i, listHeader) in cellHeaders.withIndex()) {
            val cell = flatCells.getOrNull(i) ?: TableCell().also {
                // A document may declare more cells than the row table implied; keep them anyway.
                if (table.rows.isEmpty()) table.rows.add(TableRow())
                table.rows.last().cells.add(it)
            }
            readCell(listHeader, cell)
        }
        table.width = table.rows.firstOrNull()?.cells?.sumOf { it.width } ?: 0
        table.height = table.rows.sumOf { row -> row.cells.maxOfOrNull { it.height } ?: 0 }
        return table
    }

    private fun readCell(listHeader: HwpRecord, cell: TableCell) {
        val r = listHeader.reader()
        r.i32()                            // paragraph count
        if (r.remaining >= 4) r.i32()      // attributes
        if (r.remaining >= 2) cell.columnIndex = r.u16()
        if (r.remaining >= 2) cell.rowIndex = r.u16()
        if (r.remaining >= 2) cell.columnSpan = r.u16().coerceAtLeast(1)
        if (r.remaining >= 2) cell.rowSpan = r.u16().coerceAtLeast(1)
        if (r.remaining >= 4) cell.width = r.i32()
        if (r.remaining >= 4) cell.height = r.i32()
        if (r.remaining >= 2) cell.marginLeft = r.i16()
        if (r.remaining >= 2) cell.marginRight = r.i16()
        if (r.remaining >= 2) cell.marginTop = r.i16()
        if (r.remaining >= 2) cell.marginBottom = r.i16()
        if (r.remaining >= 2) cell.borderFillId = r.u16()

        for (child in listHeader.childrenWithTag(HwpTag.PARA_HEADER)) {
            cell.paragraphs.add(readParagraph(child))
        }
    }

    private fun parseSectionDef(record: HwpRecord): SectionDefControl {
        val control = SectionDefControl()
        record.firstChild(HwpTag.PAGE_DEF)?.let { control.pageDef = readPageDef(it) }
        return control
    }

    fun readPageDef(record: HwpRecord): PageDef {
        val r = record.reader()
        val width = r.i32()
        val height = r.i32()
        val left = r.i32()
        val right = r.i32()
        val top = r.i32()
        val bottom = r.i32()
        val header = r.i32()
        val footer = r.i32()
        val gutter = r.i32()
        val attribute = if (r.remaining >= 4) r.i32() else 0
        return PageDef(
            width = width,
            height = height,
            marginLeft = left,
            marginRight = right,
            marginTop = top,
            marginBottom = bottom,
            marginHeader = header,
            marginFooter = footer,
            marginGutter = gutter,
            landscape = attribute and 0x1 != 0,
        )
    }

    fun writePageDef(page: PageDef): ByteArray {
        val w = ByteWriter(40)
        w.i32(page.width)
        w.i32(page.height)
        w.i32(page.marginLeft)
        w.i32(page.marginRight)
        w.i32(page.marginTop)
        w.i32(page.marginBottom)
        w.i32(page.marginHeader)
        w.i32(page.marginFooter)
        w.i32(page.marginGutter)
        w.i32(if (page.landscape) 1 else 0)
        return w.toByteArray()
    }

    private fun parseHeaderFooter(record: HwpRecord, isHeader: Boolean): HeaderFooterControl {
        val control = HeaderFooterControl(isHeader)
        val listHeader = record.firstChild(HwpTag.LIST_HEADER)
        if (listHeader != null) {
            for (child in listHeader.childrenWithTag(HwpTag.PARA_HEADER)) {
                control.paragraphs.add(readParagraph(child))
            }
        }
        return control
    }

    private fun parseNote(record: HwpRecord, isFootnote: Boolean): NoteControl {
        val control = NoteControl(isFootnote)
        val listHeader = record.firstChild(HwpTag.LIST_HEADER)
        if (listHeader != null) {
            for (child in listHeader.childrenWithTag(HwpTag.PARA_HEADER)) {
                control.paragraphs.add(readParagraph(child))
            }
        }
        return control
    }

    /**
     * A general shape object. Only pictures are modelled; other shapes keep their records and are
     * shown in the editor as a placeholder.
     */
    private fun parseShape(record: HwpRecord): Control {
        val pictureRecord = record.firstChild(HwpTag.SHAPE_COMPONENT)
            ?.firstChild(HwpTag.SHAPE_COMPONENT_PICTURE)
            ?: record.firstChild(HwpTag.SHAPE_COMPONENT_PICTURE)
            ?: return OpaqueControl(CtrlId.GENERAL_SHAPE)

        val picture = PictureControl()
        // Width and height sit in the common object header, after the ctrl id, attributes and the
        // two anchor offsets.
        val sizeReader = record.reader()
        sizeReader.i32() // ctrl id
        if (sizeReader.remaining >= 4) sizeReader.i32() // attributes
        if (sizeReader.remaining >= 4) sizeReader.i32() // vertical offset
        if (sizeReader.remaining >= 4) sizeReader.i32() // horizontal offset
        if (sizeReader.remaining >= 8) {
            picture.width = sizeReader.i32()
            picture.height = sizeReader.i32()
        }

        val p = pictureRecord.reader()
        // Border colour, thickness and properties precede the crop rectangle and bin data id.
        if (p.remaining >= 4) p.i32()   // border colour
        if (p.remaining >= 4) p.i32()   // border thickness
        if (p.remaining >= 4) p.i32()   // border properties
        if (p.remaining >= 16) p.skip(16) // four crop corners
        if (p.remaining >= 8) {
            picture.cropLeft = p.i32()
            picture.cropTop = p.i32()
        }
        if (p.remaining >= 8) {
            picture.cropRight = p.i32()
            picture.cropBottom = p.i32()
        }
        if (p.remaining >= 8) p.skip(8) // inner margins
        if (p.remaining >= 2) picture.binDataId = p.u16()
        return picture
    }

    // ---- writing ---------------------------------------------------------------------------

    /**
     * Rebuilds a section's record list.
     *
     * Paragraphs the user never edited are emitted from their original records byte for byte.
     * Edited paragraphs are regenerated, keeping their control records in place.
     */
    fun writeSection(section: Section): List<HwpRecord> {
        val out = ArrayList<HwpRecord>()
        for (paragraph in section.paragraphs) {
            out.add(writeParagraph(paragraph, level = 0, pageDef = section.pageDef, sectionDirty = section.dirty))
        }
        return out
    }

    fun writeParagraph(paragraph: Paragraph, level: Int, pageDef: PageDef? = null, sectionDirty: Boolean = false): HwpRecord {
        val source = paragraph.sourceRecord
        if (!paragraph.dirty && source != null) {
            val copy = source.deepCopy()
            if (sectionDirty && pageDef != null) updatePageDef(copy, pageDef)
            return relevel(copy, level)
        }

        paragraph.normalise()
        val rawText = buildRawText(paragraph)
        val charRuns = buildCharShapeRuns(paragraph)
        val controlRecords = paragraph.items.filterIsInstance<ControlSpan>()
            .filter { it.isExtended }
            .mapNotNull { it.control?.sourceRecord?.deepCopy() }

        val header = ByteWriter(24)
        header.i32(if (rawText.isEmpty()) 0 else rawText.length)
        header.u32(paragraph.controlMask)
        header.u16(paragraph.paraShapeId)
        header.u8(paragraph.styleId)
        header.u8(paragraph.breakType)
        header.u16(charRuns.size)
        header.u16(0)   // range tag count
        // Line segments are layout, not content. Dropping them makes 한글 reflow the paragraph,
        // which is exactly what should happen after an edit.
        header.u16(0)
        header.i32(0)   // paragraph instance id

        val record = HwpRecord(HwpTag.PARA_HEADER, level, header.toByteArray())

        if (rawText.isNotEmpty()) {
            record.children.add(HwpRecord(HwpTag.PARA_TEXT, level + 1, encodeUtf16Le(rawText)))
        }
        if (charRuns.isNotEmpty()) {
            val w = ByteWriter(charRuns.size * 8)
            for ((position, shapeId) in charRuns) {
                w.i32(position)
                w.i32(shapeId)
            }
            record.children.add(HwpRecord(HwpTag.PARA_CHAR_SHAPE, level + 1, w.toByteArray()))
        }
        for (control in controlRecords) {
            record.children.add(relevel(control, level + 1))
        }
        if (pageDef != null) updatePageDef(record, pageDef)
        return record
    }

    /** Rewrites the PAGE_DEF nested under a section-definition control, if this paragraph has one. */
    private fun updatePageDef(record: HwpRecord, pageDef: PageDef) {
        record.forEachDeep { node ->
            if (node.tagId == HwpTag.PAGE_DEF) node.payload = writePageDef(pageDef)
        }
    }

    private fun relevel(record: HwpRecord, level: Int): HwpRecord {
        val moved = HwpRecord(record.tagId, level, record.payload)
        moved.forcedExtendedSize = record.forcedExtendedSize
        for (c in record.children) moved.children.add(relevel(c, level + 1))
        return moved
    }

    fun buildRawText(paragraph: Paragraph): String = buildString {
        for (item in paragraph.items) {
            when (item) {
                is TextSpan -> append(item.text)
                is CharControlSpan -> append(item.code.toChar())
                is ControlSpan -> append(controlUnits(item))
            }
        }
    }

    /**
     * The eight code units for an inline or extended control, rebuilt when the original is missing.
     *
     * Every eight-unit control shares one frame: the control character itself at both ends with six
     * information units between them. For an extended control the first two of those six hold the
     * control id. Unused information units are zero, which is why they are built from an empty
     * CharArray rather than spelled out.
     */
    private fun controlUnits(span: ControlSpan): String {
        if (span.raw.length == 8) return span.raw
        return if (span.isExtended && span.ctrlId.isNotEmpty()) {
            extendedControlUnits(span.code, span.ctrlId)
        } else {
            span.code.toChar() + String(CharArray(6)) + span.code.toChar()
        }
    }

    /** Builds the eight code units for an extended control anchored at control character [code]. */
    fun extendedControlUnits(code: Int, ctrlId: String): String {
        val idValue = ctrlIdValue(ctrlId)
        return buildString {
            append(code.toChar())
            append((idValue and 0xFFFF).toChar())
            append(((idValue ushr 16) and 0xFFFF).toChar())
            append(String(CharArray(4)))
            append(code.toChar())
        }
    }

    private fun buildCharShapeRuns(paragraph: Paragraph): List<Pair<Int, Int>> {
        val runs = ArrayList<Pair<Int, Int>>()
        var position = 0
        var lastShape = -1
        for (item in paragraph.items) {
            if (item.charShapeId != lastShape) {
                runs.add(position to item.charShapeId)
                lastShape = item.charShapeId
            }
            position += item.wcharLength
        }
        if (runs.isEmpty()) runs.add(0 to 0)
        return runs
    }

    /** Total WCHAR length, exported for tests and for the editor's position mapping. */
    fun wcharLength(items: List<ParaItem>): Int = items.sumOf { it.wcharLength }
}
