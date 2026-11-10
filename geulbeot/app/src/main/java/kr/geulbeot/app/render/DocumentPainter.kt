package kr.geulbeot.app.render

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import kr.geulbeot.hwp.model.CharShape
import kr.geulbeot.hwp.model.ControlSpan
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.LineSpacingKind
import kr.geulbeot.hwp.model.PageDef
import kr.geulbeot.hwp.model.ParaAlign
import kr.geulbeot.hwp.model.Paragraph
import kr.geulbeot.hwp.model.PictureControl
import kr.geulbeot.hwp.model.StrikeOutKind
import kr.geulbeot.hwp.model.TableControl
import kr.geulbeot.hwp.model.TextSpan
import kr.geulbeot.hwp.model.UnderlineKind
import kr.geulbeot.hwp.util.HwpColor

/**
 * Lays a document out into pages and draws them.
 *
 * Used for PDF export and for printing - the two places that need a real page, as opposed to the
 * editor, which uses ordinary text fields so that typing, selection and the Korean IME all behave
 * the way they do everywhere else on the device.
 *
 * The coordinate system is PostScript points, so one unit is 1/72 inch. HWPUNIT is 1/7200 inch,
 * which makes every conversion a division by 100 and keeps page geometry exact.
 */
class DocumentPainter(private val document: HwpDocument) {

    /** One laid-out page: the drawing commands that belong on it. */
    class Page(val widthPoints: Int, val heightPoints: Int) {
        internal val blocks = ArrayList<Block>()
    }

    internal sealed interface Block {
        val top: Float
        val height: Float
    }

    private class TextBlock(
        override val top: Float,
        override val height: Float,
        val layout: StaticLayout,
        val left: Float,
        val firstLine: Int,
        val lastLine: Int,
    ) : Block

    private class TableBlock(
        override val top: Float,
        override val height: Float,
        val left: Float,
        val table: TableControl,
        val cells: List<CellLayout>,
    ) : Block

    private class CellLayout(
        val rect: RectF,
        val layouts: List<StaticLayout>,
        val textLeft: Float,
        val textTop: Float,
    )

    private class ImageBlock(
        override val top: Float,
        override val height: Float,
        val left: Float,
        val width: Float,
        val bytes: ByteArray,
    ) : Block

    /** Lays the whole document out. Returns at least one page even for an empty document. */
    fun paginate(): List<Page> {
        val pages = ArrayList<Page>()
        for (section in document.sections) {
            val page = section.pageDef
            val pageWidth = points(page.width)
            val pageHeight = points(page.height)
            val contentLeft = points(page.marginLeft).toFloat()
            val contentTop = (points(page.marginTop) + points(page.marginHeader)).toFloat()
            val contentWidth = points(page.contentWidth).coerceAtLeast(72)
            val contentBottom = (pageHeight - points(page.marginBottom) - points(page.marginFooter)).toFloat()

            var current = Page(pageWidth, pageHeight)
            var y = contentTop

            fun newPage() {
                pages.add(current)
                current = Page(pageWidth, pageHeight)
                y = contentTop
            }

            for (paragraph in section.paragraphs) {
                val paraShape = document.paraShapeOrDefault(paragraph.paraShapeId)
                y += points(paraShape.spaceBefore)

                val indentLeft = contentLeft + points(paraShape.marginLeft)
                val indentRight = points(paraShape.marginRight)
                val width = (contentWidth - points(paraShape.marginLeft) - indentRight).coerceAtLeast(36)

                // A table anchored in this paragraph is drawn as a block of its own.
                val table = paragraph.controls().filterIsInstance<TableControl>().firstOrNull()
                if (table != null) {
                    var block = layoutTable(table, indentLeft, y, width.toFloat())
                    if (y + block.height > contentBottom && current.blocks.isNotEmpty()) {
                        newPage()
                        // The table's cell rectangles are absolute, so it has to be laid out again
                        // at the new origin rather than simply moved.
                        block = layoutTable(table, indentLeft, y, width.toFloat())
                    }
                    current.blocks.add(block)
                    y += block.height + 4f
                }

                val picture = paragraph.controls().filterIsInstance<PictureControl>().firstOrNull()
                if (picture != null) {
                    val bytes = document.binData.firstOrNull {
                        it.id == picture.binDataId || it.itemId == picture.binDataRef
                    }?.data
                    if (bytes != null) {
                        val imageWidth = points(picture.width).toFloat().coerceAtMost(width.toFloat())
                        val scale = if (picture.width > 0) imageWidth / points(picture.width) else 1f
                        val imageHeight = points(picture.height) * scale
                        if (y + imageHeight > contentBottom && current.blocks.isNotEmpty()) newPage()
                        current.blocks.add(ImageBlock(y, imageHeight, indentLeft, imageWidth, bytes))
                        y += imageHeight + 4f
                    }
                }

                val layout = layoutParagraph(paragraph, width)
                if (layout != null) {
                    var line = 0
                    while (line < layout.lineCount) {
                        val lineTop = layout.getLineTop(line).toFloat()
                        // How many lines fit in what is left of this page.
                        var last = line
                        while (
                            last + 1 < layout.lineCount &&
                            y + (layout.getLineBottom(last + 1) - lineTop) <= contentBottom
                        ) {
                            last++
                        }
                        val fitsNothing = y + (layout.getLineBottom(line) - lineTop) > contentBottom
                        if (fitsNothing && current.blocks.isNotEmpty()) {
                            newPage()
                            continue
                        }
                        val height = layout.getLineBottom(last) - lineTop
                        current.blocks.add(TextBlock(y, height.toFloat(), layout, indentLeft, line, last))
                        y += height
                        line = last + 1
                        if (line < layout.lineCount) newPage()
                    }
                }
                y += points(paraShape.spaceAfter)
            }
            pages.add(current)
        }
        if (pages.isEmpty()) pages.add(Page(points(PageDef().width), points(PageDef().height)))
        return pages
    }

    /** Draws one page. The canvas is expected to be in points, origin at the page's top-left. */
    fun draw(canvas: Canvas, page: Page, background: Int = Color.WHITE) {
        canvas.drawColor(background)
        for (block in page.blocks) {
            when (block) {
                is TextBlock -> drawText(canvas, block)
                is TableBlock -> drawTable(canvas, block)
                is ImageBlock -> drawImage(canvas, block)
            }
        }
    }

    private fun drawText(canvas: Canvas, block: TextBlock) {
        val lineTop = block.layout.getLineTop(block.firstLine).toFloat()
        canvas.save()
        canvas.translate(block.left, block.top - lineTop)
        canvas.clipRect(
            0f,
            lineTop,
            block.layout.width.toFloat(),
            lineTop + block.height,
        )
        block.layout.draw(canvas)
        canvas.restore()
    }

    private fun drawTable(canvas: Canvas, block: TableBlock) {
        val border = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.6f
            color = Color.rgb(0x60, 0x66, 0x70)
            isAntiAlias = true
        }
        for (cell in block.cells) {
            canvas.drawRect(cell.rect, border)
            canvas.save()
            canvas.clipRect(cell.rect)
            canvas.translate(cell.textLeft, cell.textTop)
            var offset = 0f
            for (layout in cell.layouts) {
                canvas.save()
                canvas.translate(0f, offset)
                layout.draw(canvas)
                canvas.restore()
                offset += layout.height
            }
            canvas.restore()
        }
    }

    private fun drawImage(canvas: Canvas, block: ImageBlock) {
        val options = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(block.bytes, 0, block.bytes.size, options) }.getOrNull()
            ?: return
        val destination = RectF(block.left, block.top, block.left + block.width, block.top + block.height)
        canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), destination, Paint(Paint.FILTER_BITMAP_FLAG))
        bitmap.recycle()
    }

    // ---- layout helpers ---------------------------------------------------------------------

    private fun layoutParagraph(paragraph: Paragraph, width: Int): StaticLayout? {
        val text = buildSpanned(paragraph) ?: return null
        val paraShape = document.paraShapeOrDefault(paragraph.paraShapeId)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 10f
        }
        val alignment = when (paraShape.align) {
            ParaAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            ParaAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val multiplier = when (paraShape.lineSpacingKind) {
            LineSpacingKind.PERCENT -> (paraShape.lineSpacing / 100f).coerceIn(0.5f, 5f)
            else -> 1.15f
        }
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setLineSpacing(0f, multiplier)
            .setIncludePad(false)
        // Indent the first line the way the paragraph asks. A negative value is a hanging indent,
        // which StaticLayout cannot express directly, so only positive indents are applied here.
        if (paraShape.indent > 0) {
            builder.setIndents(intArrayOf(points(paraShape.indent)), null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && paraShape.align == ParaAlign.JUSTIFY) {
            builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        }
        return builder.build()
    }

    /** Turns a paragraph's runs into styled text. */
    private fun buildSpanned(paragraph: Paragraph): SpannableStringBuilder? {
        val builder = SpannableStringBuilder()
        for (item in paragraph.items) {
            val start = builder.length
            when (item) {
                is TextSpan -> builder.append(item.text)
                is kr.geulbeot.hwp.model.CharControlSpan -> when (item.code) {
                    kr.geulbeot.hwp.model.HwpChar.LINE_BREAK -> builder.append('\n')
                    kr.geulbeot.hwp.model.HwpChar.HARD_SPACE,
                    kr.geulbeot.hwp.model.HwpChar.FIXED_WIDTH_SPACE,
                    -> builder.append(' ')
                    else -> Unit
                }
                is ControlSpan -> if (item.code == kr.geulbeot.hwp.model.HwpChar.TAB) builder.append('\t')
            }
            val end = builder.length
            if (end > start) applySpans(builder, start, end, document.charShapeOrDefault(item.charShapeId))
        }
        return if (builder.isEmpty()) null else builder
    }

    private fun applySpans(builder: SpannableStringBuilder, start: Int, end: Int, shape: CharShape) {
        // The constant lives on Spanned. Kotlin will not reach it through an implementing
        // class the way Java lets you write SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE.
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        // Font size is stored in 1/100 pt and the canvas is in points, so this is a plain division.
        builder.setSpan(AbsoluteSizeSpan((shape.height / 100f).coerceAtLeast(1f).toInt(), false), start, end, flags)
        builder.setSpan(TypefaceSpan(familyFor(shape)), start, end, flags)
        val style = when {
            shape.bold && shape.italic -> Typeface.BOLD_ITALIC
            shape.bold -> Typeface.BOLD
            shape.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        if (style != Typeface.NORMAL) builder.setSpan(StyleSpan(style), start, end, flags)
        builder.setSpan(ForegroundColorSpan(HwpColor.toArgb(shape.textColor)), start, end, flags)
        if (shape.shadeColor != HwpColor.WHITE) {
            builder.setSpan(BackgroundColorSpan(HwpColor.toArgb(shape.shadeColor)), start, end, flags)
        }
        if (shape.underline != UnderlineKind.NONE) builder.setSpan(UnderlineSpan(), start, end, flags)
        if (shape.strikeOut != StrikeOutKind.NONE) builder.setSpan(StrikethroughSpan(), start, end, flags)
        if (shape.superscript) builder.setSpan(SuperscriptSpan(), start, end, flags)
        if (shape.subscript) builder.setSpan(SubscriptSpan(), start, end, flags)
    }

    /**
     * Picks a family the device actually has.
     *
     * A phone will not have 함초롬바탕 installed, so a 명조 document is drawn in the platform serif
     * face and a 고딕 one in the sans face. That keeps the character of the document even though the
     * exact metrics cannot match 한글 on a desktop.
     */
    private fun familyFor(shape: CharShape): String {
        val name = document.fontName(shape.fontIds.getOrElse(0) { 0 }).lowercase()
        val serif = listOf("바탕", "명조", "궁서", "batang", "myeongjo", "serif").any { name.contains(it) }
        return if (serif) "serif" else "sans-serif"
    }

    private fun layoutTable(table: TableControl, left: Float, top: Float, availableWidth: Float): TableBlock {
        val columnCount = table.rows.firstOrNull()?.cells?.size ?: 1
        val declaredWidth = points(table.width).toFloat()
        val scale = if (declaredWidth > 0 && declaredWidth > availableWidth) availableWidth / declaredWidth else 1f

        val cells = ArrayList<CellLayout>()
        var y = top
        for (row in table.rows) {
            var x = left
            var rowHeight = 0f
            val rowCells = ArrayList<Pair<RectF, List<StaticLayout>>>()
            for (cell in row.cells) {
                val cellWidth = if (cell.width > 0) {
                    points(cell.width) * scale
                } else {
                    availableWidth / columnCount
                }
                val padLeft = points(cell.marginLeft).toFloat()
                val padTop = points(cell.marginTop).toFloat()
                val textWidth = (cellWidth - padLeft - points(cell.marginRight)).toInt().coerceAtLeast(20)
                val layouts = cell.paragraphs.mapNotNull { layoutParagraph(it, textWidth) }
                val textHeight = layouts.sumOf { it.height }.toFloat()
                val cellHeight = maxOf(
                    points(cell.height) * scale,
                    textHeight + padTop + points(cell.marginBottom),
                    16f,
                )
                rowCells.add(RectF(x, y, x + cellWidth, y + cellHeight) to layouts)
                rowHeight = maxOf(rowHeight, cellHeight)
                x += cellWidth
            }
            for ((rect, layouts) in rowCells) {
                val padded = RectF(rect.left, y, rect.right, y + rowHeight)
                cells.add(
                    CellLayout(
                        rect = padded,
                        layouts = layouts,
                        textLeft = padded.left + 3f,
                        textTop = padded.top + 3f,
                    ),
                )
            }
            y += rowHeight
        }
        return TableBlock(top, y - top, left, table, cells)
    }

    companion object {
        /** HWPUNIT is 1/7200 inch and a point is 1/72 inch. */
        fun points(hwpUnit: Int): Int = Math.round(hwpUnit / 100.0).toInt()
    }
}
