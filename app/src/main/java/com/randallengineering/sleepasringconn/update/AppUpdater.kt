package com.randallengineering.sleepasringconn.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the repo's latest GitHub release and installs its APK over this app.
 * The repo is public, so no token is needed. Every release is signed with the same
 * sideload key as the app, so Android accepts it as an update.
 */
object AppUpdater {

    const val REPO = "Flexingg/SleepAsRingConn-"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/$REPO/releases"

    data class Release(
        val tagName: String,
        val title: String,
        val notes: String,
        val apkName: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val versionCode: Long?,
        val htmlUrl: String
    )

    sealed interface CheckResult {
        data class UpdateAvailable(val release: Release) : CheckResult
        data class UpToDate(val release: Release?) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    fun installedVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "SleepAsRingConn-updater")
            }
            try {
                when (val code = conn.responseCode) {
                    200 -> Unit
                    404 -> return@withContext CheckResult.UpToDate(null) // no releases yet
                    else -> return@withContext CheckResult.Failed("GitHub returned HTTP $code")
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val assets = json.optJSONArray("assets")
                var apk: JSONObject? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) { apk = a; break }
                    }
                }
                if (apk == null) return@withContext CheckResult.Failed("Latest release has no APK attached")

                val tag = json.optString("tag_name")
                val release = Release(
                    tagName = tag,
                    title = json.optString("name").ifBlank { tag },
                    notes = json.optString("body"),
                    apkName = apk.optString("name"),
                    apkUrl = apk.optString("browser_download_url"),
                    apkSizeBytes = apk.optLong("size"),
                    versionCode = UpdateVersion.remoteVersionCode(tag, apk.optString("name")),
                    htmlUrl = json.optString("html_url", RELEASES_PAGE_URL)
                )
                if (UpdateVersion.isNewer(release.versionCode, installedVersionCode(context))) {
                    CheckResult.UpdateAvailable(release)
                } else {
                    CheckResult.UpToDate(release)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Downloads the release APK into the app cache, reporting progress 0..1 (or -1 if unknown). */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "update.apk")
            val partial = File(dir, "update.apk.part")

            var url = URL(release.apkUrl)
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "SleepAsRingConn-updater")
                }
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    val location = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    url = URL(url, location)
                    redirects++
                    continue
                }
                if (code != 200) {
                    conn.disconnect()
                    throw IllegalStateException("Download failed (HTTP $code)")
                }
                break
            }

            try {
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSizeBytes
                conn.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            // Sanity check: it must be an APK of this app, otherwise don't offer to install it.
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(partial.path, 0)
            if (info == null || info.packageName != context.packageName) {
                partial.delete()
                throw IllegalStateException("Downloaded file is not a SleepAsRingConn APK")
            }
            partial.renameTo(target)
            target
        }

    /** Android 8+ needs the user to allow this app to install other APKs. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Hands the APK to the system installer, which asks the user to confirm the update. */
    fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openReleasesPage(context)
        }
    }

    fun openReleasesPage(context: Context, url: String = RELEASES_PAGE_URL) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
