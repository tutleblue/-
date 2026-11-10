package kr.geulbeot.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.geulbeot.app.editor.EditorViewModel
import kr.geulbeot.app.editor.ParagraphAddress
import kr.geulbeot.app.ui.theme.DocumentFonts
import kr.geulbeot.app.ui.theme.documentColors
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.LineSpacingKind
import kr.geulbeot.hwp.model.ParaAlign
import kr.geulbeot.hwp.model.Paragraph
import kr.geulbeot.hwp.model.StrikeOutKind
import kr.geulbeot.hwp.model.TableControl
import kr.geulbeot.hwp.model.UnderlineKind
import kr.geulbeot.hwp.util.HwpColor
import kr.geulbeot.hwp.util.HwpUnit

/**
 * One paragraph, as an editable field.
 *
 * The editor uses real text fields rather than a custom-drawn page. That is the decision that makes
 * Korean input work: the IME's composing text, selection handles, magnifier, cursor movement and
 * autocorrect all belong to the platform's text field, and reimplementing them for Hangul would be
 * a large amount of work to end up slightly worse.
 *
 * Character-level formatting is shown through a [VisualTransformation], which restyles the text
 * without changing it, so offsets stay one to one and nothing has to be remapped.
 */
@Composable
fun ParagraphEditor(
    viewModel: EditorViewModel,
    document: HwpDocument,
    paragraph: Paragraph,
    address: ParagraphAddress,
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    val colors = documentColors
    val isFocused = viewModel.caret == address
    val focusRequester = remember { FocusRequester() }

    // The field owns the text while it is being typed into; the model owns it the rest of the time.
    // Re-seeding the field on every model change would restart the IME's composing text mid-word,
    // so the two are only reconciled when they actually differ - after undo, replace, or a
    // formatting command that rewrites the run structure.
    var value by remember(paragraph) { mutableStateOf(TextFieldValue(paragraph.text)) }

    LaunchedEffect(viewModel.revision, paragraph) {
        val modelText = paragraph.text
        if (modelText != value.text) {
            val caret = viewModel.selectionStart.coerceIn(0, modelText.length)
            val end = viewModel.selectionEnd.coerceIn(caret, modelText.length)
            value = TextFieldValue(modelText, TextRange(caret, end))
        } else if (isFocused && value.selection.start != viewModel.selectionStart) {
            val caret = viewModel.selectionStart.coerceIn(0, modelText.length)
            val end = viewModel.selectionEnd.coerceIn(caret, modelText.length)
            value = value.copy(selection = TextRange(caret, end))
        }
    }

    LaunchedEffect(isFocused, paragraph) {
        if (isFocused) runCatching { focusRequester.requestFocus() }
    }

    val paraShape = document.paraShapeOrDefault(paragraph.paraShapeId)
    val firstShape = document.charShapeOrDefault(paragraph.items.firstOrNull()?.charShapeId ?: 0)
    val baseSize = (firstShape.sizePt * zoom).coerceIn(4.0, 200.0).sp

    val style = LocalTextStyle.current.merge(
        TextStyle(
            color = colors.ink,
            fontSize = baseSize,
            lineHeight = lineHeightFor(paraShape.lineSpacingKind, paraShape.lineSpacing, baseSize),
            textAlign = when (paraShape.align) {
                ParaAlign.LEFT -> TextAlign.Start
                ParaAlign.RIGHT -> TextAlign.End
                ParaAlign.CENTER -> TextAlign.Center
                ParaAlign.JUSTIFY -> TextAlign.Justify
                else -> TextAlign.Start
            },
            fontFamily = DocumentFonts.familyFor(document.fontName(firstShape.fontIds.getOrElse(0) { 0 })),
        ),
    )

    val transformation = remember(paragraph, viewModel.revision, viewModel.findHits, address) {
        ParagraphFormatting(
            paragraph = paragraph,
            document = document,
            zoom = zoom,
            highlights = viewModel.hitsIn(address),
            highlightColor = colors.searchHighlight,
            currentColor = colors.searchCurrent,
            defaultInk = colors.ink,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = (HwpUnit.toPoint(paraShape.marginLeft) * zoom).dp,
                end = (HwpUnit.toPoint(paraShape.marginRight) * zoom).dp,
                top = (HwpUnit.toPoint(paraShape.spaceBefore) * zoom).dp,
                bottom = (HwpUnit.toPoint(paraShape.spaceAfter) * zoom).dp,
            ),
    ) {
        BasicTextField(
            value = value,
            onValueChange = { next ->
                val previous = value.text
                value = next
                viewModel.setCaret(address, next.selection.start, next.selection.end)
                val newline = insertedNewlineIndex(previous, next.text)
                if (newline >= 0) {
                    // Enter starts a new paragraph, as it does in 한글. A line break inside the same
                    // paragraph is a separate command on the ribbon.
                    val withoutBreak = next.text.removeRange(newline, newline + 1)
                    viewModel.onParagraphTextChanged(address, withoutBreak)
                    viewModel.setSelection(newline, newline)
                    viewModel.splitParagraphAtCaret()
                } else {
                    viewModel.onParagraphTextChanged(address, next.text)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    // Backspace at the very start joins this paragraph to the one above, the way it
                    // does in any word processor. Soft keyboards do not always deliver this as a key
                    // event, so 문단 합치기 is also on the ribbon.
                    val atStart = value.selection.collapsed && value.selection.start == 0
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && atStart) {
                        viewModel.setCaret(address, 0, 0)
                        viewModel.mergeWithPrevious()
                    } else {
                        false
                    }
                }
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        viewModel.setCaret(address, value.selection.start, value.selection.end)
                    }
                },
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )

        // Tables and pictures anchored in this paragraph are drawn under its text, which is where
        // 한글 puts an object anchored as a character on its own line.
        for (control in paragraph.controls()) {
            when (control) {
                is TableControl -> TableEditor(
                    viewModel = viewModel,
                    document = document,
                    table = control,
                    address = address,
                    tableIndex = paragraph.controls().indexOf(control),
                    zoom = zoom,
                )
                else -> Unit
            }
        }
    }
}

/**
 * Finds the newline the user just typed, or -1.
 *
 * Only a single inserted character counts: pasting text that contains newlines should keep them as
 * line breaks rather than exploding into a paragraph per line.
 */
private fun insertedNewlineIndex(previous: String, next: String): Int {
    if (next.length != previous.length + 1) return -1
    var i = 0
    while (i < previous.length && previous[i] == next[i]) i++
    return if (i < next.length && next[i] == '\n') i else -1
}

private fun lineHeightFor(kind: LineSpacingKind, value: Int, fontSize: TextUnit): TextUnit = when (kind) {
    LineSpacingKind.PERCENT -> (fontSize.value * (value / 100f).coerceIn(0.6f, 4f)).sp
    LineSpacingKind.FIXED -> (HwpUnit.toPoint(value)).sp
    else -> (fontSize.value * 1.4f).sp
}

/**
 * Applies each run's character shape to the text as it is displayed.
 *
 * The text itself is untouched, so [OffsetMapping.Identity] is correct and the caret never lands
 * somewhere the user did not put it.
 */
private class ParagraphFormatting(
    private val paragraph: Paragraph,
    private val document: HwpDocument,
    private val zoom: Float,
    private val highlights: List<IntRange>,
    private val highlightColor: Color,
    private val currentColor: Color,
    private val defaultInk: Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text.text)
        var position = 0
        for (item in paragraph.items) {
            val length = item.displayLength
            if (length == 0) continue
            val start = position
            val end = (position + length).coerceAtMost(text.length)
            position += length
            if (start >= text.length) break
            if (end <= start) continue

            val shape = document.charShapeOrDefault(item.charShapeId)
            val decoration = when {
                shape.underline != UnderlineKind.NONE && shape.strikeOut != StrikeOutKind.NONE ->
                    TextDecoration.Underline + TextDecoration.LineThrough
                shape.underline != UnderlineKind.NONE -> TextDecoration.Underline
                shape.strikeOut != StrikeOutKind.NONE -> TextDecoration.LineThrough
                else -> null
            }
            builder.addStyle(
                SpanStyle(
                    color = if (shape.textColor == HwpColor.BLACK) defaultInk else Color(HwpColor.toArgb(shape.textColor)),
                    fontSize = (shape.sizePt * zoom).coerceIn(4.0, 200.0).sp,
                    fontWeight = if (shape.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (shape.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = decoration,
                    background = if (shape.shadeColor != HwpColor.WHITE) {
                        Color(HwpColor.toArgb(shape.shadeColor))
                    } else {
                        Color.Unspecified
                    },
                    baselineShift = when {
                        shape.superscript -> BaselineShift.Superscript
                        shape.subscript -> BaselineShift.Subscript
                        else -> BaselineShift.None
                    },
                    fontFamily = DocumentFonts.familyFor(
                        document.fontName(shape.fontIds.getOrElse(0) { 0 }),
                    ),
                ),
                start,
                end,
            )
        }

        for (range in highlights) {
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (end > start) builder.addStyle(SpanStyle(background = highlightColor), start, end)
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/**
 * A table, as a grid of editable cells.
 *
 * Column widths come from the document so a table looks the way it was made, but they are scaled to
 * the available width - a table laid out for A4 would otherwise run off the side of a phone.
 */
@Composable
private fun TableEditor(
    viewModel: EditorViewModel,
    document: HwpDocument,
    table: TableControl,
    address: ParagraphAddress,
    tableIndex: Int,
    zoom: Float,
) {
    val colors = documentColors
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        for ((rowIndex, row) in table.rows.withIndex()) {
            val totalWidth = row.cells.sumOf { it.width }.coerceAtLeast(1)
            Row(modifier = Modifier.fillMaxWidth()) {
                for ((columnIndex, cell) in row.cells.withIndex()) {
                    val weight = (cell.width.toFloat() / totalWidth).coerceAtLeast(0.05f)
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .border(1.dp, colors.tableGrid)
                            .background(
                                if (viewModel.caret.tableIndex == tableIndex &&
                                    viewModel.caret.row == rowIndex &&
                                    viewModel.caret.column == columnIndex
                                ) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                    ) {
                        Column {
                            for ((cellParagraphIndex, cellParagraph) in cell.paragraphs.withIndex()) {
                                ParagraphEditor(
                                    viewModel = viewModel,
                                    document = document,
                                    paragraph = cellParagraph,
                                    address = address.copy(
                                        tableIndex = tableIndex,
                                        row = rowIndex,
                                        column = columnIndex,
                                        cellParagraphIndex = cellParagraphIndex,
                                    ),
                                    zoom = zoom,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
