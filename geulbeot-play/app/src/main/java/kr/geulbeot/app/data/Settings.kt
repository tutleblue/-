package kr.geulbeot.app.data

import android.content.Context
import android.content.SharedPreferences
import kr.geulbeot.app.ui.theme.ThemeChoice
import kr.geulbeot.hwp.model.DocumentFormat

/**
 * User preferences.
 *
 * Backed by SharedPreferences rather than a database: there are a dozen values, they are read at
 * startup and written when a switch is flipped, and the platform already persists them safely.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var theme: ThemeChoice
        get() = runCatching { ThemeChoice.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeChoice.SYSTEM)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /**
     * Format used when saving a document that has no file yet.
     *
     * Defaults to HWPX: it is the published standard, it is what this app writes most faithfully,
     * and 한글 2014 and later open it directly.
     */
    var defaultNewFormat: DocumentFormat
        get() = runCatching { DocumentFormat.valueOf(prefs.getString(KEY_NEW_FORMAT, null) ?: "") }
            .getOrDefault(DocumentFormat.HWPX)
        set(value) = prefs.edit().putString(KEY_NEW_FORMAT, value.name).apply()

    var autoSaveEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOSAVE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSAVE, value).apply()

    /** Editor zoom, as a percentage of the page's natural width. */
    var zoomPercent: Int
        get() = prefs.getInt(KEY_ZOOM, 100).coerceIn(50, 300)
        set(value) = prefs.edit().putInt(KEY_ZOOM, value.coerceIn(50, 300)).apply()

    /** Show the page outline (margins, page breaks) rather than a continuous column of text. */
    var pageLayoutView: Boolean
        get() = prefs.getBoolean(KEY_PAGE_VIEW, true)
        set(value) = prefs.edit().putBoolean(KEY_PAGE_VIEW, value).apply()

    var showFormattingMarks: Boolean
        get() = prefs.getBoolean(KEY_FORMATTING_MARKS, false)
        set(value) = prefs.edit().putBoolean(KEY_FORMATTING_MARKS, value).apply()

    companion object {
        const val FILE_NAME = "geulbeot_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_NEW_FORMAT = "new_format"
        private const val KEY_AUTOSAVE = "autosave"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_PAGE_VIEW = "page_view"
        private const val KEY_FORMATTING_MARKS = "formatting_marks"
    }
}
