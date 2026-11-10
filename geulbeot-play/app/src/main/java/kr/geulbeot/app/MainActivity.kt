package kr.geulbeot.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kr.geulbeot.app.editor.EditorViewModel
import kr.geulbeot.app.shortcut.Shortcuts
import kr.geulbeot.app.ui.GeulbeotRoot
import kr.geulbeot.app.ui.theme.GeulbeotTheme

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    /** Set when the app was started by a shortcut that asks for the file picker. */
    private var pendingOpenRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // From targetSdk 36 the system draws the app behind the status and navigation bars whether
        // it asks to or not, so ask for it explicitly and let the screens pad themselves. Each
        // screen is a Scaffold, which already inserts the system bar insets it is given.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        setContent {
            GeulbeotTheme(themeChoice = viewModel.themeChoice) {
                GeulbeotRoot(
                    viewModel = viewModel,
                    widthSizeClass = calculateWindowWidthClass(),
                    requestOpenOnStart = pendingOpenRequest,
                    onOpenRequestConsumed = { pendingOpenRequest = false },
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    @androidx.compose.runtime.Composable
    private fun calculateWindowWidthClass() = calculateWindowSizeClass(this).widthSizeClass

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Turns however the app was started into an action.
     *
     * A document can arrive four ways: opened from a file manager, shared from another app, picked
     * from a launcher shortcut, or chosen inside the app. The first three land here.
     */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { openUri(it) }
            Intent.ACTION_SEND -> {
                val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                uri?.let { openUri(it) }
            }
            Shortcuts.ACTION_OPEN_URI -> intent.getStringExtra(Shortcuts.EXTRA_URI)?.let { openUri(Uri.parse(it)) }
            Shortcuts.ACTION_NEW_DOCUMENT -> viewModel.newDocument()
            Shortcuts.ACTION_OPEN_DOCUMENT -> pendingOpenRequest = true
        }
    }

    private fun openUri(uri: Uri) {
        // A URI handed over by another app carries a one-off grant. Reading it immediately is the
        // only way to be sure the grant is still valid.
        viewModel.open(uri)
    }
}
