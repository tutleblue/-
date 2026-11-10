package kr.geulbeot.hwp.api

import kr.geulbeot.hwp.model.CharControlSpan
import kr.geulbeot.hwp.model.CharShape
import kr.geulbeot.hwp.model.ControlSpan
import kr.geulbeot.hwp.model.HwpChar
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.ParaAlign
import kr.geulbeot.hwp.model.ParaItem
import kr.geulbeot.hwp.model.ParaShape
import kr.geulbeot.hwp.model.Paragraph
import kr.geulbeot.hwp.model.PictureControl
import kr.geulbeot.hwp.model.Section
import kr.geulbeot.hwp.model.TableCell
import kr.geulbeot.hwp.model.TableControl
import kr.geulbeot.hwp.model.TableRow
import kr.geulbeot.hwp.model.TextSpan
import kr.geulbeot.hwp.util.HwpUnit

/**
 * Content and formatting operations on a document.
 *
 * All positions here are in *editor* coordinates - offsets into [Paragraph.text] - because that is
 * what a text field reports. The translation to the file format's code-unit positions happens
 * inside, which is the only place it should happen.
 *
 * Every operation marks what it touched dirty, which is what tells the writers to regenerate those
 * records rather than copy the originals.
 */
class DocumentEditor(val document: HwpDocument) {

    // ---- text ---------------------------------------------------------------------------------

    /**
     * Applies a text change to a paragraph, keeping the formatting of the text that did not change.
     *
     * A text field hands back the whole new string rather than a description of the edit, so the
     * unchanged prefix and suffix are found first and only the middle is rebuilt. Typing inside a
     * bold run therefore stays bold, which is what a person expects.
     */
    fun replaceParagraphText(paragraph: Paragraph, newText: String) {
        val oldText = paragraph.text
        if (oldText == newText) return

        var prefix = 0
        val maxPrefix = minOf(oldText.length, newText.length)
        while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++

        var suffix = 0
        while (
            suffix < maxPrefix - prefix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) {
            suffix++
        }

        val removedEnd = oldText.length - suffix
        val inserted = newText.substring(prefix, newText.length - suffix)
        val shapeId = paragraph.charShapeAtDisplay(prefix)

        val head = sliceItems(paragraph.items, 0, prefix)
        val tail = sliceItems(paragraph.items, removedEnd, oldText.length)
        // A replacement spanning the whole paragraph has no position left to hang these on, so
        // anchored markers move to the front rather than disappearing.
        val anchored = zeroWidthItems(paragraph.items, 0, paragraph.displayLength + 1)

        paragraph.items.clear()
        paragraph.items.addAll(anchored)
        paragraph.items.addAll(head)
        if (inserted.isNotEmpty()) paragraph.items.addAll(textItems(inserted, shapeId))
        paragraph.items.addAll(tail)
        if (paragraph.items.none { it is TextSpan }) paragraph.items.add(TextSpan("", shapeId))
        paragraph.normalise()
        paragraph.dirty = true
    }

    /**
     * Splits a paragraph at [offset], returning the new paragraph that holds everything after it.
     * This is what pressing Enter does.
     */
    fun splitParagraph(section: Section, index: Int, offset: Int): Paragraph {
        val paragraph = section.paragraphs[index]
        val length = paragraph.displayLength
        val head = sliceItems(paragraph.items, 0, offset)
        val tail = sliceItems(paragraph.items, offset, length)

        // Markers before the cut stay with the first half; those after it follow the text. The
        // section definition is the exception: it belongs to the section's first paragraph wherever
        // it happens to sit, because that is where 한글 looks for the page setup.
        val before = zeroWidthItems(paragraph.items, 0, offset + 1)
        val after = zeroWidthItems(paragraph.items, offset + 1, length + 1)
        val structuralAfter = after.filterIsInstance<ControlSpan>().filter { it.isStructural }

        paragraph.items.clear()
        paragraph.items.addAll(before)
        paragraph.items.addAll(structuralAfter)
        paragraph.items.addAll(head)
        if (paragraph.items.none { it is TextSpan }) {
            paragraph.items.add(TextSpan("", paragraph.charShapeAtDisplay(0)))
        }
        paragraph.normalise()
        paragraph.dirty = true

        val next = Paragraph(paragraph.paraShapeId, paragraph.styleId)
        next.items.addAll(after.filterNot { it in structuralAfter })
        next.items.addAll(tail)
        if (next.items.none { it is TextSpan }) {
            next.items.add(TextSpan("", paragraph.charShapeAtDisplay(offset)))
        }
        next.dirty = true
        section.paragraphs.add(index + 1, next)
        section.dirty = true
        return next
    }

    /** Joins a paragraph with the one before it. This is what Backspace at offset zero does. */
    fun mergeWithPrevious(section: Section, index: Int): Int {
        if (index <= 0) return 0
        val previous = section.paragraphs[index - 1]
        val current = section.paragraphs[index]
        val joinOffset = previous.displayLength
        previous.items.addAll(current.items)
        previous.normalise()
        previous.dirty = true
        section.paragraphs.removeAt(index)
        section.dirty = true
        return joinOffset
    }

    fun insertParagraphAfter(section: Section, index: Int, text: String = ""): Paragraph {
        val template = section.paragraphs.getOrNull(index)
        val paragraph = Paragraph(template?.paraShapeId ?: 0, template?.styleId ?: 0)
        paragraph.appendText(text, template?.charShapeAtDisplay(0) ?: 0)
        if (paragraph.items.isEmpty()) paragraph.items.add(TextSpan("", template?.charShapeAtDisplay(0) ?: 0))
        section.paragraphs.add(index + 1, paragraph)
        section.dirty = true
        return paragraph
    }

    fun deleteParagraph(section: Section, index: Int): Boolean {
        if (section.paragraphs.size <= 1) return false
        // The first paragraph anchors the section definition; move it rather than lose it.
        if (index == 0) {
            val structural = section.paragraphs[0].items.filterIsInstance<ControlSpan>().filter { it.isStructural }
            section.paragraphs[1].items.addAll(0, structural)
            section.paragraphs[1].dirty = true
        }
        section.paragraphs.removeAt(index)
        section.dirty = true
        return true
    }

    // ---- character formatting -------------------------------------------------------------

    /**
     * Applies a change to the character shape over a range of a paragraph.
     *
     * Shapes are shared across the whole document, so the change is expressed as a transform and the
     * result is looked up in (or appended to) the document's shape table rather than mutated in
     * place - editing one word must not restyle every other run that happened to share its shape.
     */
    fun applyCharShape(paragraph: Paragraph, start: Int, end: Int, transform: (CharShape) -> CharShape) {
        if (end <= start) return
        splitAtDisplayOffset(paragraph, start)
        splitAtDisplayOffset(paragraph, end)

        var position = 0
        for (item in paragraph.items) {
            val itemEnd = position + item.displayLength
            if (position >= start && itemEnd <= end && item.displayLength > 0) {
                val current = document.charShapeOrDefault(item.charShapeId).copyShape()
                item.charShapeId = document.ensureCharShape(transform(current))
            }
            position = itemEnd
        }
        paragraph.normalise()
        paragraph.dirty = true
    }

    /** The shape in force over a range, or the shape at [start] when the range is not uniform. */
    fun charShapeAt(paragraph: Paragraph, start: Int, end: Int): CharShape {
        val id = paragraph.charShapeAtDisplay(if (end > start) start else (start - 1).coerceAtLeast(0))
        return document.charShapeOrDefault(id)
    }

    fun toggleBold(paragraph: Paragraph, start: Int, end: Int) =
        applyCharShape(paragraph, start, end) { it.apply { bold = !bold } }

    fun toggleItalic(paragraph: Paragraph, start: Int, end: Int) =
        applyCharShape(paragraph, start, end) { it.apply { italic = !italic } }

    fun toggleUnderline(paragraph: Paragraph, start: Int, end: Int) =
        applyCharShape(paragraph, start, end) {
            it.apply {
                underline = if (underline == kr.geulbeot.hwp.model.UnderlineKind.NONE) {
                    kr.geulbeot.hwp.model.UnderlineKind.BOTTOM
                } else {
                    kr.geulbeot.hwp.model.UnderlineKind.NONE
                }
            }
        }

    fun toggleStrikeOut(paragraph: Paragraph, start: Int, end: Int) =
        applyCharShape(paragraph, start, end) {
            it.apply {
                strikeOut = if (strikeOut == kr.geulbeot.hwp.model.StrikeOutKind.NONE) {
                    kr.geulbeot.hwp.model.StrikeOutKind.SINGLE
                } else {
                    kr.geulbeot.hwp.model.StrikeOutKind.NONE
                }
            }
        }

    fun setFontSize(paragraph: Paragraph, start: Int, end: Int, points: Double) =
        applyCharShape(paragraph, start, end) { it.apply { sizePt = points } }

    fun setFont(paragraph: Paragraph, start: Int, end: Int, fontName: String) {
        val fontId = document.ensureFont(fontName)
        applyCharShape(paragraph, start, end) { it.apply { setFontForAllLanguages(fontId) } }
    }

    fun setTextColor(paragraph: Paragraph, start: Int, end: Int, colorRef: Int) =
        applyCharShape(paragraph, start, end) { it.apply { textColor = colorRef } }

    fun setHighlight(paragraph: Paragraph, start: Int, end: Int, colorRef: Int) =
        applyCharShape(paragraph, start, end) { it.apply { shadeColor = colorRef } }

    fun setSuperscript(paragraph: Paragraph, start: Int, end: Int, enabled: Boolean) =
        applyCharShape(paragraph, start, end) { it.apply { superscript = enabled; if (enabled) subscript = false } }

    fun setSubscript(paragraph: Paragraph, start: Int, end: Int, enabled: Boolean) =
        applyCharShape(paragraph, start, end) { it.apply { subscript = enabled; if (enabled) superscript = false } }

    // ---- paragraph formatting ---------------------------------------------------------------

    fun applyParaShape(paragraph: Paragraph, transform: (ParaShape) -> ParaShape) {
        val current = document.paraShapeOrDefault(paragraph.paraShapeId).copy()
        paragraph.paraShapeId = document.ensureParaShape(transform(current))
        paragraph.dirty = true
    }

    fun setAlign(paragraph: Paragraph, align: ParaAlign) =
        applyParaShape(paragraph) { it.copy(align = align) }

    fun setLineSpacing(paragraph: Paragraph, percent: Int) = applyParaShape(paragraph) {
        it.copy(lineSpacingKind = kr.geulbeot.hwp.model.LineSpacingKind.PERCENT, lineSpacing = percent)
    }

    fun setParagraphSpacing(paragraph: Paragraph, beforePt: Double, afterPt: Double) = applyParaShape(paragraph) {
        it.copy(spaceBefore = HwpUnit.fromPoint(beforePt), spaceAfter = HwpUnit.fromPoint(afterPt))
    }

    /** Nudges the left margin by one step, as the indent buttons in a toolbar do. */
    fun indent(paragraph: Paragraph, steps: Int) = applyParaShape(paragraph) {
        it.copy(marginLeft = (it.marginLeft + steps * INDENT_STEP).coerceAtLeast(0))
    }

    fun setFirstLineIndent(paragraph: Paragraph, points: Double) = applyParaShape(paragraph) {
        it.copy(indent = HwpUnit.fromPoint(points))
    }

    // ---- objects -----------------------------------------------------------------------------

    /** Inserts a table at [offset], with every cell sharing the width available on the page. */
    fun insertTable(section: Section, paragraph: Paragraph, offset: Int, rows: Int, columns: Int): TableControl {
        require(rows > 0 && columns > 0) { "표는 최소 1행 1열이어야 합니다." }
        val table = TableControl()
        table.rowCount = rows
        table.columnCount = columns
        table.insideMarginLeft = HwpUnit.fromMm(1.8)
        table.insideMarginRight = HwpUnit.fromMm(1.8)
        table.insideMarginTop = HwpUnit.fromMm(0.5)
        table.insideMarginBottom = HwpUnit.fromMm(0.5)

        val available = section.pageDef.contentWidth.coerceAtLeast(HwpUnit.fromMm(50.0))
        val cellWidth = available / columns
        val cellHeight = HwpUnit.fromPoint(22.0)
        val charShapeId = paragraph.charShapeAtDisplay(offset)

        for (r in 0 until rows) {
            val row = TableRow()
            for (c in 0 until columns) {
                val cell = TableCell()
                cell.rowIndex = r
                cell.columnIndex = c
                cell.width = cellWidth
                cell.height = cellHeight
                cell.marginLeft = table.insideMarginLeft
                cell.marginRight = table.insideMarginRight
                cell.marginTop = table.insideMarginTop
                cell.marginBottom = table.insideMarginBottom
                cell.paragraphs.add(Paragraph(paragraph.paraShapeId, paragraph.styleId).apply {
                    items.add(TextSpan("", charShapeId))
                })
                row.cells.add(cell)
            }
            table.rows.add(row)
        }
        table.width = cellWidth * columns
        table.height = cellHeight * rows

        insertControl(paragraph, offset, table, charShapeId)
        section.dirty = true
        return table
    }

    fun insertPicture(
        section: Section,
        paragraph: Paragraph,
        offset: Int,
        imageBytes: ByteArray,
        extension: String,
        widthHwpUnit: Int,
        heightHwpUnit: Int,
    ): PictureControl {
        val entry = document.addPicture(imageBytes, extension)
        entry.itemId = "image${entry.id}"
        entry.streamName = "${entry.itemId}.${entry.extension}"
        val picture = PictureControl()
        picture.binDataId = entry.id
        picture.binDataRef = entry.itemId
        picture.width = widthHwpUnit
        picture.height = heightHwpUnit
        insertControl(paragraph, offset, picture, paragraph.charShapeAtDisplay(offset))
        section.dirty = true
        return picture
    }

    private fun insertControl(
        paragraph: Paragraph,
        offset: Int,
        control: kr.geulbeot.hwp.model.Control,
        charShapeId: Int,
    ) {
        splitAtDisplayOffset(paragraph, offset)
        val span = ControlSpan(
            code = HwpChar.EXTENDED_OBJECT,
            raw = "",
            ctrlId = control.ctrlId,
            control = control,
            charShapeId = charShapeId,
        )
        var position = 0
        var insertAt = paragraph.items.size
        for ((index, item) in paragraph.items.withIndex()) {
            // Step past zero-width items sitting at this offset so an object never lands in front
            // of the section definition.
            if (position >= offset && item.displayLength > 0) {
                insertAt = index
                break
            }
            position += item.displayLength
        }
        paragraph.items.add(insertAt, span)
        paragraph.dirty = true
    }

    fun addTableRow(table: TableControl, afterRow: Int) {
        val template = table.rows.getOrNull(afterRow) ?: table.rows.lastOrNull() ?: return
        val row = TableRow()
        for (source in template.cells) {
            val cell = TableCell()
            cell.rowIndex = afterRow + 1
            cell.columnIndex = source.columnIndex
            cell.width = source.width
            cell.height = source.height
            cell.marginLeft = source.marginLeft
            cell.marginRight = source.marginRight
            cell.marginTop = source.marginTop
            cell.marginBottom = source.marginBottom
            cell.borderFillId = source.borderFillId
            cell.paragraphs.add(Paragraph().apply { items.add(TextSpan("", 0)) })
            row.cells.add(cell)
        }
        table.rows.add((afterRow + 1).coerceIn(0, table.rows.size), row)
        renumber(table)
    }

    fun addTableColumn(table: TableControl, afterColumn: Int) {
        for ((rowIndex, row) in table.rows.withIndex()) {
            val source = row.cells.getOrNull(afterColumn) ?: row.cells.lastOrNull() ?: continue
            val cell = TableCell()
            cell.rowIndex = rowIndex
            cell.columnIndex = afterColumn + 1
            cell.width = source.width
            cell.height = source.height
            cell.marginLeft = source.marginLeft
            cell.marginRight = source.marginRight
            cell.marginTop = source.marginTop
            cell.marginBottom = source.marginBottom
            cell.borderFillId = source.borderFillId
            cell.paragraphs.add(Paragraph().apply { items.add(TextSpan("", 0)) })
            row.cells.add((afterColumn + 1).coerceIn(0, row.cells.size), cell)
        }
        renumber(table)
    }

    fun removeTableRow(table: TableControl, row: Int) {
        if (table.rows.size <= 1) return
        table.rows.removeAt(row.coerceIn(0, table.rows.size - 1))
        renumber(table)
    }

    fun removeTableColumn(table: TableControl, column: Int) {
        if ((table.rows.firstOrNull()?.cells?.size ?: 0) <= 1) return
        for (row in table.rows) {
            if (column in row.cells.indices) row.cells.removeAt(column)
        }
        renumber(table)
    }

    private fun renumber(table: TableControl) {
        for ((r, row) in table.rows.withIndex()) {
            for ((c, cell) in row.cells.withIndex()) {
                cell.rowIndex = r
                cell.columnIndex = c
            }
        }
        table.rowCount = table.rows.size
        table.columnCount = table.rows.firstOrNull()?.cells?.size ?: 0
        table.width = table.rows.firstOrNull()?.cells?.sumOf { it.width } ?: table.width
        table.height = table.rows.sumOf { row -> row.cells.maxOfOrNull { it.height } ?: 0 }
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Splits the item that straddles [offset] so the offset falls on an item boundary. */
    private fun splitAtDisplayOffset(paragraph: Paragraph, offset: Int) {
        var position = 0
        for ((index, item) in paragraph.items.withIndex()) {
            val end = position + item.displayLength
            if (offset > position && offset < end && item is TextSpan) {
                val cut = offset - position
                val left = TextSpan(item.text.substring(0, cut), item.charShapeId)
                val right = TextSpan(item.text.substring(cut), item.charShapeId)
                paragraph.items[index] = left
                paragraph.items.add(index + 1, right)
                return
            }
            position = end
        }
    }

    private fun textItems(text: String, charShapeId: Int): List<ParaItem> {
        val items = ArrayList<ParaItem>()
        var start = 0
        for (i in text.indices) {
            when (text[i]) {
                '\n' -> {
                    if (i > start) items.add(TextSpan(text.substring(start, i), charShapeId))
                    items.add(CharControlSpan(HwpChar.LINE_BREAK, charShapeId))
                    start = i + 1
                }
                '\t' -> {
                    if (i > start) items.add(TextSpan(text.substring(start, i), charShapeId))
                    items.add(ControlSpan(HwpChar.TAB, Paragraph.tabRawUnits(), charShapeId = charShapeId))
                    start = i + 1
                }
            }
        }
        if (start < text.length) items.add(TextSpan(text.substring(start), charShapeId))
        return items
    }

    companion object {
        /** One indent step, matching 한글's default of two Hangul characters at 10pt. */
        val INDENT_STEP: Int = HwpUnit.fromPoint(20.0)

        /**
         * Takes the part of [items] between two editor offsets.
         *
         * Every item is copied. Returning shared instances would be a trap: [Paragraph.normalise]
         * merges adjacent text spans in place, so one instance appearing in two slices would grow
         * text that belongs to the other.
         *
         * Zero-width items - section definitions, bookmarks, fields - are not returned at all.
         * They have a position but no extent, so which side of a cut they belong on is a decision
         * for the caller, not something a range can answer.
         */
        fun sliceItems(items: List<ParaItem>, from: Int, to: Int): MutableList<ParaItem> {
            val out = ArrayList<ParaItem>()
            var position = 0
            for (item in items) {
                val length = item.displayLength
                val end = position + length
                if (length > 0) {
                    if (item is TextSpan) {
                        val sliceStart = maxOf(from, position)
                        val sliceEnd = minOf(to, end)
                        if (sliceEnd > sliceStart) {
                            out.add(
                                TextSpan(
                                    item.text.substring(sliceStart - position, sliceEnd - position),
                                    item.charShapeId,
                                ),
                            )
                        }
                    } else if (position >= from && end <= to) {
                        out.add(copyItem(item))
                    }
                }
                position = end
            }
            return out
        }

        /** Zero-width items whose position falls in the half-open range, copied. */
        fun zeroWidthItems(items: List<ParaItem>, from: Int, to: Int): List<ParaItem> {
            val out = ArrayList<ParaItem>()
            var position = 0
            for (item in items) {
                if (item.displayLength == 0 && position >= from && position < to) out.add(copyItem(item))
                position += item.displayLength
            }
            return out
        }

        fun copyItem(item: ParaItem): ParaItem = when (item) {
            is TextSpan -> item.copy()
            is CharControlSpan -> item.copy()
            is ControlSpan -> item.copy()
        }
    }
}

/** The character shape in force at an editor offset. */
fun Paragraph.charShapeAtDisplay(offset: Int): Int {
    var position = 0
    var last = items.firstOrNull()?.charShapeId ?: 0
    for (item in items) {
        val end = position + item.displayLength
        if (item.displayLength > 0) {
            if (offset < end) return item.charShapeId
            last = item.charShapeId
        }
        position = end
    }
    return last
}
