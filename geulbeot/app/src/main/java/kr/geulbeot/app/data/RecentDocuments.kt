package kr.geulbeot.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One entry in the document list.
 *
 * [uri] is a Storage Access Framework document URI the app took a persistable permission on, so it
 * still resolves after a restart.
 */
data class RecentDocument(
    val uri: String,
    val displayName: String,
    val format: String,
    val lastOpenedMillis: Long,
    val sizeBytes: Long = 0,
    val snippet: String = "",
    val pinned: Boolean = false,
) {
    val parsedUri: Uri get() = Uri.parse(uri)

    val extension: String get() = displayName.substringAfterLast('.', "").uppercase()
}

/**
 * The recent-document list.
 *
 * Stored as a small JSON file. A database would be more machinery than a list of at most a few
 * dozen rows justifies, and a single file is trivially backed up and inspected.
 */
class RecentDocuments(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    fun load(): List<RecentDocument> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        RecentDocument(
                            uri = o.getString("uri"),
                            displayName = o.optString("name", "문서"),
                            format = o.optString("format", ""),
                            lastOpenedMillis = o.optLong("opened", 0L),
                            sizeBytes = o.optLong("size", 0L),
                            snippet = o.optString("snippet", ""),
                            pinned = o.optBoolean("pinned", false),
                        ),
                    )
                }
            }.sortedWith(compareByDescending<RecentDocument> { it.pinned }.thenByDescending { it.lastOpenedMillis })
        } catch (_: Exception) {
            // A corrupt list is not worth failing a launch over; start clean.
            emptyList()
        }
    }

    @Synchronized
    fun record(entry: RecentDocument) {
        val existing = load().filterNot { it.uri == entry.uri }
        val pinnedBefore = load().firstOrNull { it.uri == entry.uri }?.pinned ?: false
        save(listOf(entry.copy(pinned = entry.pinned || pinnedBefore)) + existing)
    }

    @Synchronized
    fun remove(uri: String) {
        save(load().filterNot { it.uri == uri })
    }

    @Synchronized
    fun setPinned(uri: String, pinned: Boolean) {
        save(load().map { if (it.uri == uri) it.copy(pinned = pinned) else it })
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun save(entries: List<RecentDocument>) {
        val trimmed = entries.take(MAX_ENTRIES)
        val array = JSONArray()
        for (e in trimmed) {
            array.put(
                JSONObject().apply {
                    put("uri", e.uri)
                    put("name", e.displayName)
                    put("format", e.format)
                    put("opened", e.lastOpenedMillis)
                    put("size", e.sizeBytes)
                    put("snippet", e.snippet)
                    put("pinned", e.pinned)
                },
            )
        }
        runCatching { file.writeText(array.toString()) }
    }

    companion object {
        const val FILE_NAME = "recent_documents.json"
        private const val MAX_ENTRIES = 60
    }
}
