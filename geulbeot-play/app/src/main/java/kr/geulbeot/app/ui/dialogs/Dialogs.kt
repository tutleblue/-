package kr.geulbeot.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.geulbeot.app.editor.EditorViewModel
import kr.geulbeot.app.ui.AppActions
import kr.geulbeot.app.ui.theme.DocumentFonts
import kr.geulbeot.hwp.model.DocumentFormat
import kr.geulbeot.hwp.model.DocumentStatistics
import kr.geulbeot.hwp.model.DocumentSummary
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.PageDef
import kr.geulbeot.hwp.model.ParaShape
import kr.geulbeot.hwp.util.HwpColor
import kr.geulbeot.hwp.util.HwpUnit
import java.util.Locale

/**
 * Saving, exporting, sharing and printing, in one place.
 *
 * These are all the same decision - "what do I want out of this document, and where does it go" -
 * so they are one sheet with a format to choose rather than four menu items in three menus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    viewModel: EditorViewModel,
    actions: AppActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "저장하고 내보내기",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
            )

            if (viewModel.documentUri != null) {
                ListItem(
                    headlineContent = { Text("이 파일에 저장") },
                    supportingContent = { Text(viewModel.displayName) },
                    leadingContent = { Icon(Icons.Filled.Save, contentDescription = null) },
                    modifier = Modifier.clickable {
                        onDismiss()
                        viewModel.save { }
                    },
                )
            }

            ListItem(
                headlineContent = { Text("한글 표준 문서로 저장 (.hwpx)") },
                supportingContent = { Text("한글 2014 이후에서 열립니다. 이 앱이 가장 정확하게 쓰는 형식입니다.") },
                leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDismiss()
                    actions.saveAs(DocumentFormat.HWPX)
                },
            )
            ListItem(
                headlineContent = { Text("한글 문서로 저장 (.hwp)") },
                supportingContent = {
                    Text(
                        if (viewModel.document?.format == DocumentFormat.HWP5) {
                            "원본과 같은 형식입니다. 이 앱이 해석하지 못한 부분은 원본 그대로 보존됩니다."
                        } else {
                            "옛 버전 한글과도 열립니다. 새로 만든 문서는 .hwpx 쪽이 더 안전합니다."
                        },
                    )
                },
                leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDismiss()
                    actions.saveAs(DocumentFormat.HWP5)
                },
            )
            ListItem(
                headlineContent = { Text("텍스트로 저장 (.txt)") },
                supportingContent = { Text("서식 없이 글자만 남습니다.") },
                leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDismiss()
                    actions.saveAs(DocumentFormat.PLAIN_TEXT)
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            ListItem(
                headlineContent = { Text("PDF로 내보내기") },
                supportingContent = { Text("쪽 나눔과 여백이 적용된 인쇄용 문서를 만듭니다.") },
                leadingContent = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDismiss()
                    actions.exportPdf()
                },
            )
            ListItem(
                headlineContent = { Text("다른 앱으로 보내기") },
                supportingContent = { Text("메신저나 메일로 문서를 그대로 보냅니다.") },
                leadingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDismiss()
                    viewModel.shareAs(context, viewModel.document?.format ?: DocumentFormat.HWPX)
                },
            )
            ListItem(
                headlineContent = { Text("인쇄") },
                supportingContent = { Text("기기에 연결된 프린터나 'PDF로 저장'을 쓸 수 있습니다.") },
                leadingContent = { Icon(Icons.Filled.Print, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDismiss()
                    viewModel.print(context)
                },
            )
        }
    }
}

@Composable
fun FontPickerDialog(
    document: HwpDocument,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Fonts already in the document come first: those are the ones the document actually uses, and
    // picking one of them does not grow the font table.
    val inDocument = document.fontFaces.map { it.name }.filter { it.isNotBlank() }
    val available = (inDocument + HwpDocument.COMMON_FONTS).distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("글꼴") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(available) { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(name)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (name == current) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "선택됨",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Spacer(Modifier.width(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                name,
                                fontFamily = DocumentFonts.familyFor(name),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (name in inDocument) {
                                Text(
                                    "문서에서 쓰는 글꼴",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/**
 * The colour palette.
 *
 * These are the swatches 한글 offers in its own colour picker, which makes a document coloured here
 * look the same as one coloured there.
 */
private val PALETTE: List<Pair<String, Int>> = listOf(
    "검정" to 0x000000,
    "진회색" to 0x404040,
    "회색" to 0x808080,
    "연회색" to 0xC0C0C0,
    "하양" to 0xFFFFFF,
    "빨강" to 0x0000FF,
    "주황" to 0x0080FF,
    "노랑" to 0x00FFFF,
    "연두" to 0x00FF80,
    "초록" to 0x008000,
    "청록" to 0x808000,
    "파랑" to 0xFF0000,
    "남색" to 0x800000,
    "보라" to 0xFF0080,
    "자주" to 0x800080,
    "분홍" to 0xC0C0FF,
    "갈색" to 0x004080,
    "베이지" to 0xC0E0FF,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HwpColorPickerDialog(
    title: String,
    current: Int,
    includeNone: Boolean = false,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (includeNone) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(HwpColor.WHITE)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("없음", style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for ((name, colorRef) in PALETTE) {
                        val selected = colorRef == current
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(HwpColor.toArgb(colorRef)), CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                )
                                .clickable {
                                    onPick(colorRef)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = name,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (colorRef == 0xFFFFFF) Color.Black else Color.White,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
fun StyleDialog(
    document: HwpDocument,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("스타일") },
        text = {
            if (document.styles.isEmpty()) {
                Text("이 문서에는 정의된 스타일이 없습니다.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    items(document.styles.indices.toList()) { index ->
                        val style = document.styles[index]
                        val shape = document.charShapeOrDefault(style.charShapeId)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(index)
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Text(
                                style.name.ifBlank { "이름 없는 스타일" },
                                fontWeight = if (shape.bold) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = DocumentFonts.familyFor(
                                    document.fontName(shape.fontIds.getOrElse(0) { 0 }),
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "${document.fontName(shape.fontIds.getOrElse(0) { 0 })} · ${formatPoint(shape.sizePt)}pt",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
fun LineSpacingDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    // 한글's own presets. 160% is what its 바탕글 style uses, which is why it is the middle option.
    val presets = listOf(100, 120, 130, 150, 160, 180, 200, 250, 300)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("줄 간격") },
        text = {
            Column {
                for (value in presets) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(value)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == current, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text("$value%")
                        if (value == 160) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "기본",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
fun ParagraphSpacingDialog(
    current: ParaShape,
    onApply: (Double, Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var before by remember { mutableStateOf(formatPoint(HwpUnit.toPoint(current.spaceBefore))) }
    var after by remember { mutableStateOf(formatPoint(HwpUnit.toPoint(current.spaceAfter))) }
    var firstLine by remember { mutableStateOf(formatPoint(HwpUnit.toPoint(current.indent))) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("문단 간격과 들여쓰기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = before,
                    onValueChange = { before = it },
                    label = { Text("문단 위 여백 (pt)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = after,
                    onValueChange = { after = it },
                    label = { Text("문단 아래 여백 (pt)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = firstLine,
                    onValueChange = { firstLine = it },
                    label = { Text("첫 줄 들여쓰기 (pt)") },
                    supportingText = { Text("음수를 넣으면 내어쓰기가 됩니다.") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    before.toDoubleOrNull() ?: 0.0,
                    after.toDoubleOrNull() ?: 0.0,
                    firstLine.toDoubleOrNull() ?: 0.0,
                )
                onDismiss()
            }) { Text("적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun TableInsertDialog(onInsert: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf("3") }
    var columns by remember { mutableStateOf("3") }
    val rowCount = rows.toIntOrNull()
    val columnCount = columns.toIntOrNull()
    val valid = rowCount != null && columnCount != null && rowCount in 1..100 && columnCount in 1..30

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("표 넣기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = rows,
                    onValueChange = { rows = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("줄 수") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = columns,
                    onValueChange = { columns = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("칸 수") },
                    singleLine = true,
                )
                if (!valid) {
                    Text(
                        "줄은 1~100, 칸은 1~30까지 넣을 수 있습니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onInsert(rowCount ?: 3, columnCount ?: 3)
                    onDismiss()
                },
            ) { Text("넣기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageSetupDialog(current: PageDef, onApply: (PageDef) -> Unit, onDismiss: () -> Unit) {
    var width by remember { mutableStateOf(current.width) }
    var height by remember { mutableStateOf(current.height) }
    var landscape by remember { mutableStateOf(current.landscape) }
    var left by remember { mutableStateOf(formatMm(current.marginLeft)) }
    var right by remember { mutableStateOf(formatMm(current.marginRight)) }
    var top by remember { mutableStateOf(formatMm(current.marginTop)) }
    var bottom by remember { mutableStateOf(formatMm(current.marginBottom)) }

    val papers = listOf(
        "A4" to (HwpUnit.A4_WIDTH to HwpUnit.A4_HEIGHT),
        "B5" to (HwpUnit.B5_WIDTH to HwpUnit.B5_HEIGHT),
        "레터" to (HwpUnit.LETTER_WIDTH to HwpUnit.LETTER_HEIGHT),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("쪽 설정") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("용지", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((name, size) in papers) {
                        FilterChip(
                            selected = width == size.first && height == size.second,
                            onClick = {
                                width = size.first
                                height = size.second
                            },
                            label = { Text(name) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !landscape,
                        onClick = { landscape = false },
                        label = { Text("세로") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = landscape,
                        onClick = { landscape = true },
                        label = { Text("가로") },
                    )
                }

                HorizontalDivider()
                Text("여백 (mm)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = top,
                        onValueChange = { top = it },
                        label = { Text("위") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = bottom,
                        onValueChange = { bottom = it },
                        label = { Text("아래") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = left,
                        onValueChange = { left = it },
                        label = { Text("왼쪽") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = right,
                        onValueChange = { right = it },
                        label = { Text("오른쪽") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    current.copy(
                        width = width,
                        height = height,
                        landscape = landscape,
                        marginLeft = parseMm(left, current.marginLeft),
                        marginRight = parseMm(right, current.marginRight),
                        marginTop = parseMm(top, current.marginTop),
                        marginBottom = parseMm(bottom, current.marginBottom),
                    ),
                )
                onDismiss()
            }) { Text("적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun DocumentInfoDialog(
    summary: DocumentSummary,
    onApply: (String, String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(summary.title) }
    var author by remember { mutableStateOf(summary.author) }
    var subject by remember { mutableStateOf(summary.subject) }
    var keywords by remember { mutableStateOf(summary.keywords) }
    var comments by remember { mutableStateOf(summary.comments) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("문서 정보") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "여기에 적은 내용만 문서에 저장됩니다. 기기나 계정 정보가 저절로 들어가지 않습니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(title, { title = it }, label = { Text("제목") }, singleLine = true)
                OutlinedTextField(author, { author = it }, label = { Text("지은이") }, singleLine = true)
                OutlinedTextField(subject, { subject = it }, label = { Text("주제") }, singleLine = true)
                OutlinedTextField(keywords, { keywords = it }, label = { Text("키워드") }, singleLine = true)
                OutlinedTextField(comments, { comments = it }, label = { Text("메모") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(title, author, subject, keywords, comments)
                onDismiss()
            }) { Text("적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun StatisticsDialog(statistics: DocumentStatistics?, pageCount: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("문서 통계") },
        text = {
            if (statistics == null) {
                Text("열린 문서가 없습니다.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow("쪽", "${pageCount}쪽")
                    StatRow("구역", "${statistics.sections}개")
                    StatRow("문단", "${statistics.paragraphs}개")
                    StatRow("글자 (공백 포함)", "${statistics.characters}자")
                    StatRow("글자 (공백 제외)", "${statistics.charactersWithoutSpaces}자")
                    StatRow("낱말", "${statistics.words}개")
                    StatRow("표", "${statistics.tables}개")
                    StatRow("그림", "${statistics.pictures}개")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatPoint(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else String.format(Locale.KOREA, "%.1f", value)

private fun formatMm(hwpUnit: Int): String = String.format(Locale.KOREA, "%.0f", HwpUnit.toMm(hwpUnit))

private fun parseMm(text: String, fallback: Int): Int =
    text.trim().toDoubleOrNull()?.let { HwpUnit.fromMm(it.coerceIn(0.0, 200.0)) } ?: fallback
