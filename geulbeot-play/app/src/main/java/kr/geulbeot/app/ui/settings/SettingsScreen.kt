package kr.geulbeot.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.geulbeot.app.BuildConfig
import kr.geulbeot.app.editor.EditorViewModel
import kr.geulbeot.app.ui.theme.ThemeChoice
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
