package kr.geulbeot.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.geulbeot.app.BuildConfig
import kr.geulbeot.app.editor.EditorViewModel
import kr.geulbeot.app.editor.UserMessage
import kr.geulbeot.app.ui.theme.ThemeChoice
import kr.geulbeot.app.update.UpdateChecker
import kr.geulbeot.app.update.UpdateInstaller
import kr.geulbeot.app.update.UpdateResult
import kr.geulbeot.hwp.model.DocumentFormat

/**
 * Settings.
 *
 * Kept to the handful of choices that actually change how the app behaves. Everything else -
 * formatting, page setup, view options - belongs to the document or the toolbar, not here.
 */
@Composable
fun SettingsScreen(
    viewModel: EditorViewModel,
    onDismiss: () -> Unit,
    onClearRecents: () -> Unit,
) {
    val settings = viewModel.settings
    val theme = viewModel.themeChoice
    var newFormat by remember { mutableStateOf(settings.defaultNewFormat) }
    var autoSave by remember { mutableStateOf(settings.autoSaveEnabled) }
    var updateOnStart by remember { mutableStateOf(settings.checkUpdatesOnStart) }
    var updateOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("설정") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionLabel("화면")
                for (choice in ThemeChoice.entries) {
                    ChoiceRow(
                        label = choice.label,
                        selected = theme == choice,
                        onClick = { viewModel.setTheme(choice) },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionLabel("새 문서의 저장 형식")
                ChoiceRow(
                    label = "한글 표준 문서 (.hwpx)",
                    description = "국가표준(KS X 6101) 형식. 이 앱이 가장 정확하게 씁니다.",
                    selected = newFormat == DocumentFormat.HWPX,
                    onClick = {
                        newFormat = DocumentFormat.HWPX
                        settings.defaultNewFormat = DocumentFormat.HWPX
                    },
                )
                ChoiceRow(
                    label = "한글 문서 (.hwp)",
                    description = "옛 버전 한글과도 열립니다. 새 문서는 구역 정의를 직접 만들어야 해서 .hwpx보다 확실하지 않습니다.",
                    selected = newFormat == DocumentFormat.HWP5,
                    onClick = {
                        newFormat = DocumentFormat.HWP5
                        settings.defaultNewFormat = DocumentFormat.HWP5
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionLabel("편집")
                SwitchRow(
                    label = "자동 저장",
                    description = "편집 내용을 앱 안에 임시로 보관해 두었다가, 앱이 닫혀도 복구할 수 있게 합니다. 원본 파일은 직접 저장할 때만 바뀝니다.",
                    checked = autoSave,
                    onCheckedChange = {
                        autoSave = it
                        settings.autoSaveEnabled = it
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionLabel("업데이트")
                SwitchRow(
                    label = "앱을 켤 때 새 버전 확인",
                    description = "GitHub의 공개 릴리즈만 확인합니다. 문서 내용은 어디에도 보내지 않습니다.",
                    checked = updateOnStart,
                    onCheckedChange = {
                        updateOnStart = it
                        settings.checkUpdatesOnStart = it
                    },
                )
                TextButton(onClick = { updateOpen = true }) { Text("지금 업데이트 확인") }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionLabel("문서함")
                TextButton(onClick = onClearRecents) { Text("최근 문서 목록 비우기") }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "글벗 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "한글 문서를 기기 안에서만 다룹니다. 문서를 서버로 보내지 않고, 저장소 권한도 요구하지 않습니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )

    if (updateOpen) {
        UpdateDialog(viewModel = viewModel, onDismiss = { updateOpen = false })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    description: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Checking for, and applying, a new release.
 *
 * Every step is the user's: the app looks, shows what changed, and only downloads and installs if
 * they say so. Android will ask again before the package is installed, which is exactly right.
 */
@Composable
fun UpdateDialog(viewModel: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checker = remember { UpdateChecker() }

    var result by remember { mutableStateOf<UpdateResult?>(null) }
    var checking by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        result = withContext(Dispatchers.IO) { checker.check() }
        viewModel.settings.lastUpdateCheckMillis = System.currentTimeMillis()
        checking = false
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("업데이트") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "현재 버전 ${checker.currentVersion()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    checking -> {
                        Text("GitHub에서 새 버전을 확인하는 중입니다…")
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    downloading -> {
                        Text("내려받는 중입니다…")
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    result is UpdateResult.UpToDate -> Text("최신 버전을 쓰고 있습니다.")
                    result is UpdateResult.Failed -> {
                        val failure = result as UpdateResult.Failed
                        Text("업데이트를 확인하지 못했습니다.")
                        Text(
                            failure.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "지금 쓰는 버전은 그대로 사용할 수 있습니다.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    result is UpdateResult.Available -> {
                        val available = result as UpdateResult.Available
                        Text(
                            "새 버전 ${available.version}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                            ) {
                                Text(available.changelog, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (available.downloadUrl == null) {
                            Text(
                                "이 릴리즈에는 설치 파일이 없습니다. GitHub 릴리즈 페이지에서 직접 받아 주세요.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val available = result as? UpdateResult.Available
            when {
                available == null || downloading -> TextButton(
                    onClick = onDismiss,
                    enabled = !downloading,
                ) { Text("닫기") }

                available.downloadUrl == null -> TextButton(onClick = {
                    context.startActivity(UpdateInstaller.browseIntent(available.releaseUrl))
                    onDismiss()
                }) { Text("릴리즈 페이지 열기") }

                !UpdateInstaller.canInstall(context) -> TextButton(onClick = {
                    // Android will not let an app install a package until the user allows it for
                    // that app specifically. Send them to the screen where they can.
                    context.startActivity(UpdateInstaller.permissionIntent(context))
                }) { Text("설치 권한 허용") }

                else -> TextButton(onClick = {
                    downloading = true
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            checker.download(context, available.downloadUrl) { received, total ->
                                if (total > 0) progress = (received.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                        downloading = false
                        if (file == null) {
                            viewModel.message = UserMessage("설치 파일을 내려받지 못했습니다.")
                        } else {
                            runCatching { context.startActivity(UpdateInstaller.installIntent(context, file)) }
                                .onFailure { viewModel.message = UserMessage("설치를 시작하지 못했습니다.") }
                        }
                        onDismiss()
                    }
                }) { Text("내려받아 설치") }
            }
        },
        dismissButton = {
            val available = result as? UpdateResult.Available
            if (available != null && !downloading) {
                TextButton(onClick = {
                    viewModel.settings.skippedUpdateVersion = available.version
                    onDismiss()
                }) { Text("이 버전 건너뛰기") }
            }
        },
    )
}
