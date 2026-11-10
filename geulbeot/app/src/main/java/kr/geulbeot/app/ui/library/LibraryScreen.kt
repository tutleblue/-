package kr.geulbeot.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.geulbeot.app.data.DocumentStore
import kr.geulbeot.app.data.RecentDocument
import kr.geulbeot.app.data.RecentDocuments
import kr.geulbeot.app.editor.EditorViewModel
import kr.geulbeot.app.shortcut.Shortcuts
import kr.geulbeot.app.ui.AppActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The document list.
 *
 * This is the only way into a document, on purpose: an "open" item in a menu elsewhere would be the
 * same action in a second place. Everything to do with getting a document - new, open, recent,
 * pinned - lives here and nowhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: EditorViewModel,
    recents: RecentDocuments,
    store: DocumentStore,
    actions: AppActions,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(emptyList<RecentDocument>()) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey, viewModel.revision) {
        entries = recents.load()
        Shortcuts.publishRecent(context, entries)
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("글벗", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "한글 문서 편집기",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = actions.showSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "설정")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (viewModel.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = actions.newDocument, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("새 문서")
                }
                OutlinedButton(onClick = actions.openPicker, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("문서 열기")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (entries.isEmpty()) {
                EmptyLibrary(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Text(
                            "최근 문서",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                    }
                    items(entries, key = { it.uri }) { entry ->
                        RecentRow(
                            entry = entry,
                            selected = viewModel.documentUri?.toString() == entry.uri,
                            compact = compact,
                            onOpen = { actions.openUri(entry.parsedUri) },
                            onPin = {
                                recents.setPinned(entry.uri, !entry.pinned)
                                reloadKey++
                            },
                            onAddToHome = {
                                val added = Shortcuts.requestPin(context, entry.parsedUri, entry.displayName)
                                viewModel.message = if (added) {
                                    kr.geulbeot.app.editor.UserMessage("홈 화면에 바로가기를 추가했습니다.")
                                } else {
                                    kr.geulbeot.app.editor.UserMessage("이 런처는 바로가기 추가를 지원하지 않습니다.")
                                }
                            },
                            onRemove = {
                                store.releasePermission(entry.parsedUri)
                                recents.remove(entry.uri)
                                reloadKey++
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))
            Text("아직 연 문서가 없습니다", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "위에서 새 문서를 만들거나, 기기에 있는 .hwp · .hwpx 파일을 열어 보세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentRow(
    entry: RecentDocument,
    selected: Boolean,
    compact: Boolean,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onAddToHome: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .background(background, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatBadge(entry.extension.ifEmpty { "HWP" })
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.pinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "고정됨",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.snippet.isNotBlank() && !compact) {
                Text(
                    entry.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                buildString {
                    append(formatDate(entry.lastOpenedMillis))
                    if (entry.sizeBytes > 0) {
                        append(" · ")
                        append(formatSize(entry.sizeBytes))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "더 보기")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (entry.pinned) "고정 해제" else "목록 위에 고정") },
                    onClick = { menuOpen = false; onPin() },
                )
                DropdownMenuItem(
                    text = { Text("홈 화면에 바로가기 추가") },
                    onClick = { menuOpen = false; onAddToHome() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("목록에서 지우기") },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}

/** A small square telling the format apart at a glance, the way a file manager does. */
@Composable
private fun FormatBadge(extension: String) {
    val color = when (extension.uppercase()) {
        "HWPX" -> MaterialTheme.colorScheme.tertiary
        "TXT" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            extension.uppercase().take(4),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatDate(millis: Long): String {
    if (millis <= 0) return "날짜 없음"
    val now = System.currentTimeMillis()
    val elapsed = now - millis
    return when {
        elapsed < 60_000 -> "방금"
        elapsed < 3_600_000 -> "${elapsed / 60_000}분 전"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000}시간 전"
        elapsed < 7 * 86_400_000L -> "${elapsed / 86_400_000}일 전"
        else -> SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA).format(Date(millis))
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.KOREA, "%.1f MB", bytes / (1024.0 * 1024.0))
}
