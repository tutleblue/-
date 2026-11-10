package kr.geulbeot.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kr.geulbeot.app.data.DocumentStore
import kr.geulbeot.app.data.RecentDocuments
import kr.geulbeot.app.editor.EditorViewModel
import kr.geulbeot.app.editor.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.geulbeot.app.shortcut.Shortcuts
import kr.geulbeot.app.ui.editor.EditorScreen
import kr.geulbeot.app.ui.library.LibraryScreen
import kr.geulbeot.app.ui.settings.SettingsScreen
import kr.geulbeot.app.ui.settings.UpdateDialog
import kr.geulbeot.app.update.UpdateChecker
import kr.geulbeot.app.update.UpdateResult
import kr.geulbeot.hwp.model.DocumentFormat

/**
 * The whole app.
 *
 * Layout follows the window rather than the device: a phone, and a tablet in portrait, get one
 * pane at a time; a wide window gets the document list and the editor side by side, which is what
 * makes a tablet worth using for this.
 */
@Composable
fun GeulbeotRoot(
    viewModel: EditorViewModel,
    widthSizeClass: WindowWidthSizeClass,
    requestOpenOnStart: Boolean,
    onOpenRequestConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { DocumentStore(context) }
    val recents = remember { RecentDocuments(context) }
    val snackbarHost = remember { SnackbarHostState() }

    val twoPane = widthSizeClass == WindowWidthSizeClass.Expanded
    var libraryVisible by rememberSaveable { mutableStateOf(true) }
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var updateVisible by rememberSaveable { mutableStateOf(false) }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.open(uri)
            libraryVisible = false
        }
    }

    // One launcher per format: the system dialog needs the media type up front so it can suggest
    // the right place and extension.
    val saveHwpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-hwp"),
    ) { uri -> uri?.let { viewModel.saveAs(it, DocumentFormat.HWP5) } }

    val saveHwpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/hwp+zip"),
    ) { uri -> uri?.let { viewModel.saveAs(it, DocumentFormat.HWPX) } }

    val saveTextLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { viewModel.saveAs(it, DocumentFormat.PLAIN_TEXT) } }

    val savePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> uri?.let { viewModel.exportPdfTo(it) } }

    val insertImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.insertPictureFrom(it) } }

    LaunchedEffect(requestOpenOnStart) {
        if (requestOpenOnStart) {
            openLauncher.launch(store.openMimeTypes)
            onOpenRequestConsumed()
        }
    }

    // A document opening from outside the app should land in the editor, not behind the list.
    LaunchedEffect(viewModel.documentUri, viewModel.document) {
        if (viewModel.document != null && !twoPane) libraryVisible = false
    }

    // Look for a newer release at most once a day, in the background, and only mention it if
    // there is one the user has not already chosen to skip. A failed check says nothing: the app
    // works the same either way, and an error toast about GitHub would be noise.
    LaunchedEffect(Unit) {
        val settings = viewModel.settings
        if (!settings.checkUpdatesOnStart) return@LaunchedEffect
        val since = System.currentTimeMillis() - settings.lastUpdateCheckMillis
        if (since < 24L * 60 * 60 * 1000) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { UpdateChecker().check() }
        settings.lastUpdateCheckMillis = System.currentTimeMillis()
        if (result is UpdateResult.Available && result.version != settings.skippedUpdateVersion) {
            viewModel.message = UserMessage(
                "새 버전 ${result.version}이(가) 있습니다.",
                actionLabel = "보기",
                action = { updateVisible = true },
            )
        }
    }

    LaunchedEffect(viewModel.message) {
        val message = viewModel.message ?: return@LaunchedEffect
        val result = snackbarHost.showSnackbar(
            message = message.text,
            actionLabel = message.actionLabel,
            duration = if (message.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) message.action?.invoke()
        viewModel.consumeMessage()
    }

    val actions = remember(viewModel) {
        AppActions(
            openPicker = { openLauncher.launch(store.openMimeTypes) },
            newDocument = {
                viewModel.newDocument()
                libraryVisible = false
            },
            openUri = {
                viewModel.open(it)
                libraryVisible = false
            },
            saveAs = { format ->
                val base = viewModel.displayName.substringBeforeLast('.', viewModel.displayName)
                when (format) {
                    DocumentFormat.HWP5 -> saveHwpLauncher.launch("$base.hwp")
                    DocumentFormat.HWPX -> saveHwpxLauncher.launch("$base.hwpx")
                    DocumentFormat.PLAIN_TEXT -> saveTextLauncher.launch("$base.txt")
                }
            },
            exportPdf = {
                val base = viewModel.displayName.substringBeforeLast('.', viewModel.displayName)
                savePdfLauncher.launch("$base.pdf")
            },
            insertImage = { insertImageLauncher.launch(arrayOf("image/*")) },
            showLibrary = { libraryVisible = true },
            showSettings = { settingsVisible = true },
        )
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (twoPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    LibraryScreen(
                        viewModel = viewModel,
                        recents = recents,
                        store = store,
                        actions = actions,
                        compact = true,
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EditorScreen(
                        viewModel = viewModel,
                        actions = actions,
                        showBackToLibrary = false,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else if (libraryVisible || viewModel.document == null) {
                LibraryScreen(
                    viewModel = viewModel,
                    recents = recents,
                    store = store,
                    actions = actions,
                    compact = false,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                EditorScreen(
                    viewModel = viewModel,
                    actions = actions,
                    showBackToLibrary = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        }
    }

    if (updateVisible) {
        UpdateDialog(viewModel = viewModel, onDismiss = { updateVisible = false })
    }

    if (settingsVisible) {
        SettingsScreen(
            viewModel = viewModel,
            onDismiss = { settingsVisible = false },
            onClearRecents = {
                recents.clear()
                Shortcuts.clearRecent(context)
            },
        )
    }
}

/** The actions every screen may need, gathered so screens do not each grow a pile of callbacks. */
class AppActions(
    val openPicker: () -> Unit,
    val newDocument: () -> Unit,
    val openUri: (Uri) -> Unit,
    val saveAs: (DocumentFormat) -> Unit,
    val exportPdf: () -> Unit,
    val insertImage: () -> Unit,
    val showLibrary: () -> Unit,
    val showSettings: () -> Unit,
)
