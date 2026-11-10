package kr.geulbeot.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kr.geulbeot.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** What a release check found. */
sealed interface UpdateResult {
    /** Already on the newest release, or newer than it. */
    data object UpToDate : UpdateResult

    data class Available(
        val version: String,
        val changelog: String,
        val downloadUrl: String?,
        val downloadSizeBytes: Long,
        val releaseUrl: String,
    ) : UpdateResult

    /** The check itself failed. The app keeps working; this is only reported if the user asked. */
    data class Failed(val reason: String) : UpdateResult
}

/**
 * Checks GitHub for a newer release of the app.
 *
 * Deliberate constraints:
 *  - Only the public releases API is used, so there is no token in the app and nothing to leak.
 *  - HTTPS only, including after a redirect. A redirect to plain HTTP is refused rather than
 *    followed, because the payload here is an installable package.
 *  - A failed check is never fatal. No network, no GitHub, a rate limit - the app carries on with
 *    the version it has.
 */
class UpdateChecker(
    private val owner: String = DEFAULT_OWNER,
    private val repository: String = DEFAULT_REPOSITORY,
) {

    fun currentVersion(): String = BuildConfig.VERSION_NAME.substringBefore('-')

    /** Blocking; call from a background dispatcher. */
    fun check(): UpdateResult {
        return try {
            val body = httpGetText("https://api.github.com/repos/$owner/$repository/releases/latest")
                ?: return UpdateResult.Failed("GitHub에 연결하지 못했습니다. 네트워크 상태를 확인해 주세요.")
            val release = JSONObject(body)
            val tag = release.optString("tag_name").ifBlank { release.optString("name") }
            if (tag.isBlank()) return UpdateResult.Failed("릴리즈 정보를 읽지 못했습니다.")

            val assets = release.optJSONArray("assets")
            var apkUrl: String? = null
            var apkSize = 0L
            var versionJsonUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name").lowercase()
                    val url = asset.optString("browser_download_url")
                    when {
                        name.endsWith(".apk") && apkUrl == null -> {
                            apkUrl = url
                            apkSize = asset.optLong("size")
                        }
                        name == "version.json" -> versionJsonUrl = url
                    }
                }
            }

            // version.json is the authority on the version and the change list; the release tag is
            // the fallback when a release was published without it.
            var version = normalizeVersion(tag)
            var changelog = release.optString("body").trim()
            if (versionJsonUrl != null) {
                httpGetText(versionJsonUrl)?.let { text ->
                    runCatching {
                        val json = JSONObject(text)
                        json.optString("version").takeIf { it.isNotBlank() }?.let { version = normalizeVersion(it) }
                        json.optString("changelog").takeIf { it.isNotBlank() }?.let { changelog = it }
                    }
                }
            }

            if (compareVersions(version, currentVersion()) <= 0) {
                UpdateResult.UpToDate
            } else {
                UpdateResult.Available(
                    version = version,
                    changelog = changelog.ifBlank { "변경 내용이 적혀 있지 않습니다." },
                    downloadUrl = apkUrl,
                    downloadSizeBytes = apkSize,
                    releaseUrl = release.optString("html_url"),
                )
            }
        } catch (e: Exception) {
            UpdateResult.Failed(e.message ?: "업데이트를 확인하지 못했습니다.")
        }
    }

    /** Downloads the release package into the app's cache. Blocking. */
    fun download(context: Context, url: String, onProgress: (Long, Long) -> Unit): File? {
        val connection = openHttps(url) ?: return null
        return try {
            val total = connection.contentLengthLong
            if (total > MAX_DOWNLOAD_BYTES) return null
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "geulbeot-update.apk")
            var received = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        received += read
                        if (received > MAX_DOWNLOAD_BYTES) {
                            target.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                        onProgress(received, total)
                    }
                }
            }
            target
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGetText(url: String): String? {
        val connection = openHttps(url) ?: return null
        return try {
            if (connection.responseCode !in 200..299) null else connection.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Opens an HTTPS connection, following redirects by hand so that a redirect away from HTTPS
     * can be refused. `HttpURLConnection` will not follow across protocols on its own, but it also
     * will not tell the caller that it stopped, so the hop is done here.
     */
    private fun openHttps(url: String): HttpsURLConnection? {
        var current = url
        repeat(MAX_REDIRECTS) {
            val parsed = URL(current)
            if (!parsed.protocol.equals("https", ignoreCase = true)) return null
            val connection = parsed.openConnection() as? HttpsURLConnection ?: return null
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            connection.setRequestProperty("User-Agent", "Geulbeot/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) return null
                current = URL(parsed, location).toString()
                return@repeat
            }
            return connection
        }
        return null
    }

    companion object {
        const val DEFAULT_OWNER = "jykim5215"
        const val DEFAULT_REPOSITORY = "-"
        private const val MAX_REDIRECTS = 5
        private const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024

        fun normalizeVersion(raw: String): String =
            raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')

        /** Semantic version comparison. Missing components count as zero, so "1.2" equals "1.2.0". */
        fun compareVersions(a: String, b: String): Int {
            val left = normalizeVersion(a).split('.')
            val right = normalizeVersion(b).split('.')
            for (i in 0 until maxOf(left.size, right.size)) {
                val l = left.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                val r = right.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                if (l != r) return l.compareTo(r)
            }
            return 0
        }
    }
}

/**
 * Handing a downloaded package to the system installer.
 *
 * Android does not let an app replace its own code, and it should not: an update is an install, and
 * an install is the user's decision. So the app downloads and then asks, and the system asks again.
 */
object UpdateInstaller {

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Sends the user to the system screen where they can allow this app to install packages. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun browseIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
}
