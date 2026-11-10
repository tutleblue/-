package kr.geulbeot.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatIndentDecrease
import androidx.compose.material.icons.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.FormatLineSpacing
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.geulbeot.app.editor.EditorViewModel
import kr.geulbeot.hwp.model.ParaAlign
import kr.geulbeot.hwp.model.StrikeOutKind
import kr.geulbeot.hwp.model.UnderlineKind
import kr.geulbeot.hwp.util.HwpColor

/** The dialogs the ribbon can ask for. The ribbon itself holds no dialog state. */
class RibbonRequests(
    val pickFont: () -> Unit,
    val pickTextColor: () -> Unit,
    val pickHighlight: () -> Unit,
    val pickStyle: () -> Unit,
    val pickLineSpacing: () -> Unit,
    val pickParagraphSpacing: () -> Unit,
    val insertTable: () -> Unit,
    val insertImage: () -> Unit,
    val pageSetup: () -> Unit,
    val documentInfo: () -> Unit,
    val statistics: () -> Unit,
)

private enum class RibbonTab(val label: String) {
    CHARACTER("서식"),
    PARAGRAPH("문단"),
    INSERT("입력"),
    PAGE("쪽"),
    VIEW("보기"),
}

/**
 * The tool bar, arranged the way 한글 arranges its ribbon.
 *
 * Tabs group tools by what they act on - characters, the paragraph, things you insert, the page,
 * the view - and each tool appears in exactly one tab. Saving, exporting and sharing are not here
 * at all: they live together behind one "내보내기" sheet, because putting the same action in a
 * toolbar and a menu is how a tool ends up with three different ways to do one thing.
 */
@Composable
fun Ribbon(
    viewModel: EditorViewModel,
    requests: RibbonRequests,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = RibbonTab.entries

    // The toolbar shows the formatting at the cursor, so it has to recompose when the document
    // changes *and* when the cursor moves. Both are Compose state; reading them here is the
    // subscription.
    val revision = viewModel.revision
    val caret = viewModel.caret
    val selectionStart = viewModel.selectionStart
    val selectionEnd = viewModel.selectionEnd

    val charShape = remember(revision, caret, selectionStart, selectionEnd) { viewModel.currentCharShape() }
    val paraShape = remember(revision, caret) { viewModel.currentParaShape() }
    val fontName = remember(revision, caret, selectionStart) { viewModel.currentFontName() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        ) {
            for ((index, entry) in tabs.withIndex()) {
                RibbonTabButton(
                    label = entry.label,
                    selected = tab == index,
                    onClick = { tab = index },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyRow(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (tabs[tab]) {
                RibbonTab.CHARACTER -> characterTools(viewModel, requests, charShape, fontName)
                RibbonTab.PARAGRAPH -> paragraphTools(viewModel, requests, paraShape)
                RibbonTab.INSERT -> insertTools(viewModel, requests)
                RibbonTab.PAGE -> pageTools(requests)
                RibbonTab.VIEW -> viewTools(viewModel, viewModel.zoomPercent, viewModel.pageLayoutView)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.characterTools(
    viewModel: EditorViewModel,
    requests: RibbonRequests,
    charShape: kr.geulbeot.hwp.model.CharShape,
    fontName: String,
) {
    item {
        WideTool(label = fontName, minWidth = 104.dp, onClick = requests.pickFont)
    }
    item {
        // Size as a stepper: on a phone this is faster than a menu, and it is the control 한글 puts
        // next to the font name.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTool(Icons.Filled.Remove, "글자 작게") {
                viewModel.setFontSize((charShape.sizePt - 1).coerceAtLeast(4.0))
            }
            Box(
                modifier = Modifier.defaultMinSize(minWidth = 46.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    formatPoint(charShape.sizePt),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            IconTool(Icons.Filled.Add, "글자 크게") {
                viewModel.setFontSize((charShape.sizePt + 1).coerceAtMost(200.0))
            }
        }
    }
    item { RibbonSeparator() }
    item {
        GlyphTool("가", "진하게", active = charShape.bold, weight = FontWeight.Bold) { viewModel.toggleBold() }
    }
    item {
        GlyphTool("가", "기울임", active = charShape.italic, italic = true) { viewModel.toggleItalic() }
    }
    item {
        GlyphTool(
            "가",
            "밑줄",
            active = charShape.underline != UnderlineKind.NONE,
            decoration = TextDecoration.Underline,
        ) { viewModel.toggleUnderline() }
    }
    item {
        GlyphTool(
            "가",
            "취소선",
            active = charShape.strikeOut != StrikeOutKind.NONE,
            decoration = TextDecoration.LineThrough,
        ) { viewModel.toggleStrikeOut() }
    }
    item { RibbonSeparator() }
    item {
        ColorTool(
            icon = Icons.Filled.FormatColorText,
            description = "글자 색",
            swatch = Color(HwpColor.toArgb(charShape.textColor)),
            onClick = requests.pickTextColor,
        )
    }
    item {
        ColorTool(
            icon = Icons.Filled.FormatColorFill,
            description = "음영 색",
            swatch = Color(HwpColor.toArgb(charShape.shadeColor)),
            onClick = requests.pickHighlight,
        )
    }
    item { RibbonSeparator() }
    item {
        GlyphTool("가²", "위 첨자", active = charShape.superscript) {
            viewModel.setSuperscript(!charShape.superscript)
        }
    }
    item {
        GlyphTool("가₂", "아래 첨자", active = charShape.subscript) {
            viewModel.setSubscript(!charShape.subscript)
        }
    }
    item { RibbonSeparator() }
    item { IconTool(Icons.Filled.Style, "스타일", onClick = requests.pickStyle) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.paragraphTools(
    viewModel: EditorViewModel,
    requests: RibbonRequests,
    paraShape: kr.geulbeot.hwp.model.ParaShape,
) {
    item {
        IconTool(Icons.Filled.FormatAlignLeft, "왼쪽 정렬", active = paraShape.align == ParaAlign.LEFT) {
            viewModel.setAlign(ParaAlign.LEFT)
        }
    }
    item {
        IconTool(Icons.Filled.FormatAlignCenter, "가운데 정렬", active = paraShape.align == ParaAlign.CENTER) {
            viewModel.setAlign(ParaAlign.CENTER)
        }
    }
    item {
        IconTool(Icons.Filled.FormatAlignRight, "오른쪽 정렬", active = paraShape.align == ParaAlign.RIGHT) {
            viewModel.setAlign(ParaAlign.RIGHT)
        }
    }
    item {
        IconTool(Icons.Filled.FormatAlignJustify, "양쪽 정렬", active = paraShape.align == ParaAlign.JUSTIFY) {
            viewModel.setAlign(ParaAlign.JUSTIFY)
        }
    }
    item { RibbonSeparator() }
    item {
        WideTool(
            label = "줄 간격 ${paraShape.lineSpacing}%",
            icon = Icons.Filled.FormatLineSpacing,
            minWidth = 112.dp,
            onClick = requests.pickLineSpacing,
        )
    }
    item { RibbonSeparator() }
    item { IconTool(Icons.Filled.FormatIndentDecrease, "내어쓰기") { viewModel.indent(-1) } }
    item { IconTool(Icons.Filled.FormatIndentIncrease, "들여쓰기") { viewModel.indent(1) } }
    item { RibbonSeparator() }
    item { IconTool(Icons.Outlined.Straighten, "문단 간격", onClick = requests.pickParagraphSpacing) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.insertTools(
    viewModel: EditorViewModel,
    requests: RibbonRequests,
) {
    item { WideTool("표", Icons.Filled.TableChart, onClick = requests.insertTable) }
    item { WideTool("그림", Icons.Filled.Image, onClick = requests.insertImage) }
    item { RibbonSeparator() }
    item { WideTool("문단 추가", Icons.Filled.KeyboardReturn) { viewModel.insertParagraphAfterCaret() } }
    item {
        WideTool("문단 합치기", Icons.Outlined.MergeType) {
            if (!viewModel.mergeWithPrevious()) {
                viewModel.message = kr.geulbeot.app.editor.UserMessage("첫 문단은 위와 합칠 수 없습니다.")
            }
        }
    }
    item { WideTool("문단 삭제", Icons.Outlined.Delete) { viewModel.deleteFocusedParagraph() } }
    item { RibbonSeparator() }
    item {
        // Table editing appears only when the caret is inside one, which is how 한글 does it too.
        if (viewModel.focusedTable() != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                WideTool("줄 추가") { viewModel.addTableRow() }
                WideTool("칸 추가") { viewModel.addTableColumn() }
                WideTool("줄 삭제") { viewModel.removeTableRow() }
                WideTool("칸 삭제") { viewModel.removeTableColumn() }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.pageTools(requests: RibbonRequests) {
    item { WideTool("쪽 설정", Icons.Outlined.Article, onClick = requests.pageSetup) }
    item { WideTool("문서 정보", Icons.Filled.Info, onClick = requests.documentInfo) }
    item { WideTool("문서 통계", Icons.Outlined.Straighten, onClick = requests.statistics) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.viewTools(
    viewModel: EditorViewModel,
    zoomPercent: Int,
    pageLayoutView: Boolean,
) {
    item { IconTool(Icons.Filled.ZoomOut, "축소") { viewModel.setZoom(zoomPercent - 10) } }
    item {
        Box(modifier = Modifier.defaultMinSize(minWidth = 56.dp), contentAlignment = Alignment.Center) {
            Text("$zoomPercent%", style = MaterialTheme.typography.labelLarge)
        }
    }
    item { IconTool(Icons.Filled.ZoomIn, "확대") { viewModel.setZoom(zoomPercent + 10) } }
    item { IconTool(Icons.Outlined.Straighten, "100%로") { viewModel.setZoom(100) } }
    item { RibbonSeparator() }
    item {
        WideTool(
            label = if (pageLayoutView) "쪽 윤곽 끄기" else "쪽 윤곽 켜기",
            minWidth = 110.dp,
            onClick = { viewModel.setPageLayout(!pageLayoutView) },
        )
    }
}

// ---- building blocks -----------------------------------------------------------------------

@Composable
private fun RibbonTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(if (selected) 26.dp else 0.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
private fun IconTool(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * A tool labelled with the letter it affects.
 *
 * 한글 marks bold, italic and underline with a styled 가 rather than B, I and U, and doing the same
 * makes the toolbar read correctly to someone who uses 한글.
 */
@Composable
private fun GlyphTool(
    glyph: String,
    description: String,
    active: Boolean = false,
    weight: FontWeight = FontWeight.Normal,
    italic: Boolean = false,
    decoration: TextDecoration? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .semanticsLabel(description),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            fontSize = 17.sp,
            fontWeight = weight,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = decoration,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun WideTool(
    label: String,
    icon: ImageVector? = null,
    minWidth: Dp = 0.dp,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .defaultMinSize(minWidth = minWidth, minHeight = 40.dp)
            .background(Color.Transparent, RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ColorTool(
    icon: ImageVector,
    description: String,
    swatch: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick)
            .padding(top = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(4.dp)
                .background(swatch, RoundedCornerShape(1.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
private fun RibbonSeparator() {
    Box(
        modifier = Modifier
            .padding(horizontal = 5.dp)
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

private fun Modifier.semanticsLabel(label: String): Modifier =
    this.then(Modifier.semantics { contentDescription = label })

/** 10.0 rather than 10, matching how 한글 shows a point size. */
private fun formatPoint(value: Double): String =
    if (value == value.toInt().toDouble()) "${value.toInt()}.0" else String.format(java.util.Locale.KOREA, "%.1f", value)
