package kr.geulbeot.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import kr.geulbeot.hwp.api.GeulbeotDocuments
import kr.geulbeot.hwp.model.DocumentFormat
import kr.geulbeot.hwp.model.HwpDocument
import java.io.File

/**
 * Reading and writing documents through the Storage Access Framework.
 *
 * The app asks for no storage permission at all. Every file it touches is one the user picked in
 * the system file dialog, and the permission granted covers only that file. That is both the modern
 * Android convention and the smallest possible claim on someone's storage.
 */
class DocumentStore(context: Context) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    /** Media types offered in the system open dialog. */
    val openMimeTypes = arrayOf(
        "application/x-hwp",
        "application/haansofthwp",
        "application/vnd.hancom.hwp",
        "application/hwp+zip",
        "application/vnd.hancom.hwpx",
        "text/plain",
        "application/octet-stream",
        "*/*",
    )

    fun readBytes(uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("파일을 열 수 없습니다. 파일이 이동되었거나 접근 권한이 사라졌을 수 있습니다.")

    fun writeBytes(uri: Uri, bytes: ByteArray) {
        // "wt" truncates. Without it a shorter document would leave the tail of the previous
        // version behind, producing a file that looks fine until something reads past the end.
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw java.io.IOException("파일에 쓸 수 없습니다. 저장 위치에 대한 권한을 확인해 주세요.")
        stream.use { it.write(bytes) }
    }

    fun open(uri: Uri): OpenedDocument {
        val bytes = readBytes(uri)
        val name = displayName(uri)
        val document = GeulbeotDocuments.open(bytes, name)
        return OpenedDocument(document, uri, name, bytes.size.toLong())
    }

    fun save(document: HwpDocument, uri: Uri, format: DocumentFormat): Int {
        val bytes = GeulbeotDocuments.save(document, format)
        writeBytes(uri, bytes)
        document.format = format
        document.markClean()
        return bytes.size
    }

    /** Keeps read and write access to a picked file across restarts. */
    fun persistPermission(uri: Uri, writable: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
    }

    fun releasePermission(uri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
            // Some providers refuse the query; fall through to the path.
        } finally {
            cursor?.close()
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "문서"
    }

    fun sizeOf(uri: Uri): Long {
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        } catch (_: Exception) {
            // Size is decoration in the document list; its absence is not an error.
        } finally {
            cursor?.close()
        }
        return 0
    }

    /** True when the document still resolves - a file may have been moved or a card removed. */
    fun exists(uri: Uri): Boolean = try {
        resolver.openInputStream(uri)?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    // ---- autosave --------------------------------------------------------------------------

    private val autoSaveDir = File(appContext.filesDir, "autosave").apply { mkdirs() }

    /**
     * Keeps a copy of unsaved work under the app's own directory.
     *
     * Saving straight back to the user's file on every keystroke would be wrong - it takes away the
     * choice to abandon an edit - so the snapshot lives here until the user saves or discards it.
     */
    fun writeAutoSave(key: String, document: HwpDocument) {
        runCatching {
            val bytes = GeulbeotDocuments.save(document, DocumentFormat.HWPX)
            File(autoSaveDir, "${key.hashCode().toUInt().toString(16)}.hwpx").writeBytes(bytes)
        }
    }

    fun readAutoSave(key: String): HwpDocument? = runCatching {
        val file = File(autoSaveDir, "${key.hashCode().toUInt().toString(16)}.hwpx")
        if (file.exists()) GeulbeotDocuments.open(file.readBytes(), file.name) else null
    }.getOrNull()

    fun clearAutoSave(key: String) {
        runCatching { File(autoSaveDir, "${key.hashCode().toUInt().toString(16)}.hwpx").delete() }
    }

    fun hasAutoSave(key: String): Boolean =
        File(autoSaveDir, "${key.hashCode().toUInt().toString(16)}.hwpx").exists()

    // ---- exports ---------------------------------------------------------------------------

    /** A file in the app's cache, ready to be handed to another app through the FileProvider. */
    fun createExportFile(name: String): File {
        val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        // Strip anything that could climb out of the directory or break a provider path.
        val safe = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "문서" }
        return File(dir, safe)
    }

    fun clearExports() {
        runCatching { File(appContext.cacheDir, "exports").deleteRecursively() }
    }
}

data class OpenedDocument(
    val document: HwpDocument,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
)
