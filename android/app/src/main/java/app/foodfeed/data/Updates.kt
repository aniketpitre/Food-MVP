package app.foodfeed.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.foodfeed.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

data class AppUpdate(val version: String, val notes: List<String>, val apkUrl: String, val sizeBytes: Long)

/**
 * In-app updates for builds installed from GitHub Releases (there is no Play Store to do it).
 * Checks the latest release, downloads its APK inside the app and hands it to Android's
 * installer. Because every release is signed with the same key, it installs over the current
 * app and keeps sign-in and settings. Android still shows its own "Install / Update" prompt;
 * apps outside the Play Store cannot update silently.
 */
object Updates {
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** True when [candidate] (like "v0.5.0" or "0.5.0") is a higher x.y.z than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The bullet lines under a "What's new" heading in the release notes, if any. */
    fun whatsNew(body: String?): List<String> {
        if (body == null) return emptyList()
        val lines = body.lines()
        val start = lines.indexOfFirst { it.trim().trimStart('#', '*', ' ').startsWith("What's new", ignoreCase = true) }
        if (start < 0) return emptyList()
        return lines.drop(start + 1)
            .takeWhile { !it.trimStart().startsWith("#") && !it.trimStart().startsWith("**") }
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotEmpty() }
            .take(6)
    }

    /** Debug builds never offer updates (they are a different app id). */
    val enabled: Boolean get() = !BuildConfig.DEBUG && BuildConfig.UPDATES_URL.isNotBlank()

    /** The newer release, or null when up to date, offline, or updates are off. */
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        runCatching {
            val req = Request.Builder().url(BuildConfig.UPDATES_URL).header("Accept", "application/vnd.github+json").build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val rel = AppJson.decodeFromString(GhRelease.serializer(), res.body!!.string())
                val apk = rel.assets.firstOrNull { it.name.endsWith(".apk") }
                if (rel.draft || rel.prerelease || apk == null || !isNewer(rel.tag, BuildConfig.VERSION_NAME)) null
                else AppUpdate(rel.tag.removePrefix("v"), whatsNew(rel.body), apk.url, apk.size)
            }
        }.getOrNull()
    }

    /** Downloads the APK into the app's cache, reporting progress from 0 to 1. */
    suspend fun download(context: Context, update: AppUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "FoodFeed-${update.version}.apk")
        val req = Request.Builder().url(update.apkUrl).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw ApiException("download_failed", "Couldn't download the update (${res.code}).")
            val body = res.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        file
    }

    /** Android 8+ asks once per app whether it may install apps. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Opens Android's installer on the downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
