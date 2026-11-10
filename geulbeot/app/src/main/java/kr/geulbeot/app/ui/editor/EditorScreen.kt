package kr.geulbeot.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.geulbeot.app.editor.EditorViewModel
import kr.geulbeot.app.editor.ParagraphAddress
import kr.geulbeot.app.editor.SaveState
import kr.geulbeot.app.ui.AppActions
import kr.geulbeot.app.ui.dialogs.DocumentInfoDialog
import kr.geulbeot.app.ui.dialogs.ExportSheet
import kr.geulbeot.app.ui.dialogs.FontPickerDialog
import kr.geulbeot.app.ui.dialogs.HwpColorPickerDialog
import kr.geulbeot.app.ui.dialogs.LineSpacingDialog
import kr.geulbeot.app.ui.dialogs.PageSetupDialog
import kr.geulbeot.app.ui.dialogs.ParagraphSpacingDialog
import kr.geulbeot.app.ui.dialogs.StatisticsDialog
import kr.geulbeot.app.ui.dialogs.StyleDialog
import kr.geulbeot.app.ui.dialogs.TableInsertDialog
import kr.geulbeot.app.ui.theme.documentColors
import kr.geulbeot.hwp.util.HwpUnit

/**
 * The editor.
 *
 * The page is drawn as a sheet inside a scrolling list of paragraphs rather than as fixed pages.
 * On a phone, paginated scrolling fights the keyboard: the caret ends up behind it and the page
 * break lands mid-sentence. The page outline still shows the margins so the layout is legible, and
 * true pagination is applied where it matters - PDF export and printing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    actions: AppActions,
    showBackToLibrary: Boolean,
    modifier: Modifier = Modifier,
) {
    val document = viewModel.document
    val colors = documentColors

    var menuOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var fontPickerOpen by remember { mutableStateOf(false) }
    var textColorOpen by remember { mutableStateOf(false) }
    var highlightOpen by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    var lineSpacingOpen by remember { mutableStateOf(false) }
    var paragraphSpacingOpen by remember { mutableStateOf(false) }
    var tableOpen by remember { mutableStateOf(false) }
    var pageSetupOpen by remember { mutableStateOf(false) }
    var documentInfoOpen by remember { mutableStateOf(false) }
    var statisticsOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Following the caret is what makes find, replace and undo feel connected to the page.
    LaunchedEffect(viewModel.caret.paragraphIndex, viewModel.currentHit) {
        if (viewModel.findHits.isNotEmpty() || viewModel.findVisible) {
            runCatching { listState.animateScrollToItem(viewModel.caret.paragraphIndex.coerceAtLeast(0)) }
        }
    }

    if (document == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "왼쪽에서 문서를 고르거나 새 문서를 만들어 주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val section = document.sections.getOrNull(viewModel.caret.sectionIndex) ?: document.sections.first()
    val zoom = viewModel.zoomPercent / 100f

    Scaffold(
        modifier = modifier,
        containerColor = colors.desk,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showBackToLibrary) {
                        IconButton(onClick = actions.showLibrary) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "문서함으로")
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            viewModel.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when (viewModel.saveState) {
                                SaveState.SAVED -> "저장됨"
                                SaveState.MODIFIED -> "수정됨"
                                SaveState.SAVING -> "저장 중…"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (viewModel.saveState == SaveState.MODIFIED) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.undo() }, enabled = viewModel.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "되돌리기")
                    }
                    IconButton(onClick = { viewModel.redo() }, enabled = viewModel.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "다시 실행")
                    }
                    IconButton(onClick = { viewModel.findVisible = !viewModel.findVisible }) {
                        Icon(Icons.Filled.Search, contentDescription = "찾기")
                    }
                    IconButton(onClick = { viewModel.save { exportOpen = true } }) {
                        Icon(Icons.Filled.Save, contentDescription = "저장")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "더 보기")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("저장하고 내보내기…") },
                                onClick = { menuOpen = false; exportOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text("설정") },
                                onClick = { menuOpen = false; actions.showSettings() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("문서 닫기") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.close()
                                    actions.showLibrary()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            Column(modifier = Modifier.imePadding()) {
                if (viewModel.findVisible) {
                    FindBar(viewModel)
                }
                Ribbon(
                    viewModel = viewModel,
                    requests = RibbonRequests(
                        pickFont = { fontPickerOpen = true },
                        pickTextColor = { textColorOpen = true },
                        pickHighlight = { highlightOpen = true },
                        pickStyle = { styleOpen = true },
                        pickLineSpacing = { lineSpacingOpen = true },
                        pickParagraphSpacing = { paragraphSpacingOpen = true },
                        insertTable = { tableOpen = true },
                        insertImage = actions.insertImage,
                        pageSetup = { pageSetupOpen = true },
                        documentInfo = { documentInfoOpen = true },
                        statistics = { statisticsOpen = true },
                    ),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (viewModel.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            val page = section.pageDef
            val sheetPadding = if (viewModel.pageLayoutView) {
                PaddingValues(
                    start = (HwpUnit.toPoint(page.marginLeft) * zoom).dp,
                    end = (HwpUnit.toPoint(page.marginRight) * zoom).dp,
                    top = (HwpUnit.toPoint(page.marginTop) * zoom).dp,
                    bottom = (HwpUnit.toPoint(page.marginBottom) * zoom).dp,
                )
            } else {
                PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.desk),
                contentPadding = PaddingValues(
                    horizontal = if (viewModel.pageLayoutView) 10.dp else 0.dp,
                    vertical = if (viewModel.pageLayoutView) 12.dp else 0.dp,
                ),
            ) {
                item(key = "sheet-top") { Spacer(Modifier.height(0.dp)) }
                // Keyed by paragraph identity so a text field keeps its state when paragraphs
                // above it are added or removed.
                itemsIndexed(
                    items = section.paragraphs,
                    key = { _, paragraph -> System.identityHashCode(paragraph) },
                ) { index, paragraph ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.paper)
                            .then(
                                if (viewModel.pageLayoutView) {
                                    Modifier.border(1.dp, colors.paperEdge)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(sheetPadding),
                    ) {
                        ParagraphEditor(
                            viewModel = viewModel,
                            document = document,
                            paragraph = paragraph,
                            address = ParagraphAddress(viewModel.caret.sectionIndex, index),
                            zoom = zoom,
                        )
                    }
                }
                item(key = "sheet-bottom") { Spacer(Modifier.height(120.dp)) }
            }
        }
    }

    if (exportOpen) {
        ExportSheet(
            viewModel = viewModel,
            actions = actions,
            onDismiss = { exportOpen = false },
        )
    }
    if (fontPickerOpen) {
        FontPickerDialog(
            document = document,
            current = viewModel.currentFontName(),
            onPick = { viewModel.setFont(it) },
            onDismiss = { fontPickerOpen = false },
        )
    }
    if (textColorOpen) {
        HwpColorPickerDialog(
            title = "글자 색",
            current = viewModel.currentCharShape().textColor,
            onPick = { viewModel.setTextColor(it) },
            onDismiss = { textColorOpen = false },
        )
    }
    if (highlightOpen) {
        HwpColorPickerDialog(
            title = "음영 색",
            current = viewModel.currentCharShape().shadeColor,
            includeNone = true,
            onPick = { viewModel.setHighlight(it) },
            onDismiss = { highlightOpen = false },
        )
    }
    if (styleOpen) {
        StyleDialog(
            document = document,
            onPick = { viewModel.applyStyle(it) },
            onDismiss = { styleOpen = false },
        )
    }
    if (lineSpacingOpen) {
        LineSpacingDialog(
            current = viewModel.currentParaShape().lineSpacing,
            onPick = { viewModel.setLineSpacing(it) },
            onDismiss = { lineSpacingOpen = false },
        )
    }
    if (paragraphSpacingOpen) {
        ParagraphSpacingDialog(
            current = viewModel.currentParaShape(),
            onApply = { before, after, firstLine ->
                viewModel.setParagraphSpacing(before, after)
                viewModel.setFirstLineIndent(firstLine)
            },
            onDismiss = { paragraphSpacingOpen = false },
        )
    }
    if (tableOpen) {
        TableInsertDialog(
            onInsert = { rows, columns -> viewModel.insertTable(rows, columns) },
            onDismiss = { tableOpen = false },
        )
    }
    if (pageSetupOpen) {
        PageSetupDialog(
            current = section.pageDef,
            onApply = { viewModel.applyPageSetup(it) },
            onDismiss = { pageSetupOpen = false },
        )
    }
    if (documentInfoOpen) {
        DocumentInfoDialog(
            summary = document.summary,
            onApply = { title, author, subject, keywords, comments ->
                viewModel.applySummary(title, author, subject, keywords, comments)
            },
            onDismiss = { documentInfoOpen = false },
        )
    }
    if (statisticsOpen) {
        StatisticsDialog(
            statistics = viewModel.statistics(),
            pageCount = remember(viewModel.revision) {
                runCatching { kr.geulbeot.app.render.DocumentExport.pageCount(document) }.getOrDefault(1)
            },
            onDismiss = { statisticsOpen = false },
        )
    }
}

/** Find and replace, shown above the ribbon so both stay reachable with the keyboard open. */
@Composable
private fun FindBar(viewModel: EditorViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = viewModel.findQuery,
                onValueChange = {
                    viewModel.findQuery = it
                    viewModel.runFind()
                },
                label = { Text("찾을 내용") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (viewModel.findHits.isEmpty()) {
                    "0"
                } else {
                    "${viewModel.currentHit + 1}/${viewModel.findHits.size}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { viewModel.findPrevious() }, enabled = viewModel.findHits.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "이전 찾기")
            }
            IconButton(onClick = { viewModel.findNext() }, enabled = viewModel.findHits.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "다음 찾기")
            }
            IconButton(onClick = { viewModel.closeFind() }) {
                Icon(Icons.Filled.Close, contentDescription = "찾기 닫기")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = viewModel.replaceText,
                onValueChange = { viewModel.replaceText = it },
                label = { Text("바꿀 내용") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            TextButton(
                onClick = { viewModel.replaceCurrent() },
                enabled = viewModel.findHits.isNotEmpty(),
            ) { Text("바꾸기") }
            TextButton(
                onClick = { viewModel.replaceAll() },
                enabled = viewModel.findQuery.isNotEmpty(),
            ) { Text("모두") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = viewModel.findOptions.caseSensitive,
                onCheckedChange = {
                    viewModel.findOptions = viewModel.findOptions.copy(caseSensitive = it)
                    viewModel.runFind()
                },
            )
            Text("대소문자 구분", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Checkbox(
                checked = viewModel.findOptions.searchTables,
                onCheckedChange = {
                    viewModel.findOptions = viewModel.findOptions.copy(searchTables = it)
                    viewModel.runFind()
                },
            )
            Text("표 안도 찾기", style = MaterialTheme.typography.labelMedium)
        }
    }
}
