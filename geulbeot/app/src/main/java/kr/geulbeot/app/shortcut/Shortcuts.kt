package kr.geulbeot.app.shortcut

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import kr.geulbeot.app.MainActivity
import kr.geulbeot.app.R
import kr.geulbeot.app.data.RecentDocument

/**
 * Launcher shortcuts.
 *
 * Two static shortcuts (new document, open document) are declared in `res/xml/shortcuts.xml`. On
 * top of those, the documents someone actually works on are published as dynamic shortcuts, so a
 * long press on the app icon goes straight into the file they had open - which is the shortest path
 * there is, and the point of the feature.
 *
 * A document can also be pinned to the home screen, giving it a real one-press launcher entry.
 */
object Shortcuts {

    const val ACTION_NEW_DOCUMENT = "kr.geulbeot.app.action.NEW_DOCUMENT"
    const val ACTION_OPEN_DOCUMENT = "kr.geulbeot.app.action.OPEN_DOCUMENT"
    const val ACTION_OPEN_URI = "kr.geulbeot.app.action.OPEN_URI"
    const val EXTRA_URI = "kr.geulbeot.app.extra.URI"

    private const val MAX_DYNAMIC = 3

    fun publishRecent(context: Context, recents: List<RecentDocument>) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcuts = recents.take(MAX_DYNAMIC).map { document ->
            ShortcutInfo.Builder(context, "recent:${document.uri.hashCode()}")
                .setShortLabel(document.displayName.take(20))
                .setLongLabel(document.displayName.take(40))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_document))
                .setIntent(openIntent(context, document.parsedUri))
                .build()
        }
        runCatching { manager.dynamicShortcuts = shortcuts }
    }

    fun clearRecent(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching { manager.removeAllDynamicShortcuts() }
    }

    /** Whether the launcher will accept a pinned shortcut at all. */
    fun canPin(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        return manager.isRequestPinShortcutSupported
    }

    /** Asks the launcher to add a home-screen shortcut that opens this document directly. */
    fun requestPin(context: Context, uri: Uri, label: String): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        val shortcut = ShortcutInfo.Builder(context, "pinned:${uri.hashCode()}")
            .setShortLabel(label.take(20))
            .setLongLabel(label.take(40))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_document))
            .setIntent(openIntent(context, uri))
            .build()
        return runCatching { manager.requestPinShortcut(shortcut, null) }.getOrDefault(false)
    }

    private fun openIntent(context: Context, uri: Uri): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_URI
            putExtra(EXTRA_URI, uri.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
}
