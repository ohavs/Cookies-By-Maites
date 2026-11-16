package com.maites.cookies.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.maites.cookies.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Ready(val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Checks GitHub's latest release (tag v0.1.<versionCode>) and installs the attached APK. */
object Updater {
    private const val API = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 15000; readTimeout = 15000
        }
        try {
            if (conn.responseCode == 404) return@withContext null
            if (conn.responseCode != 200) error("GitHub ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name").removePrefix("v")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
            val assets = json.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); break }
            }
            url?.let { Release(code, tag, json.optString("body"), it) }
        } finally { conn.disconnect() }
    }

    fun isNewer(r: Release) = r.versionCode > BuildConfig.VERSION_CODE

    suspend fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "cookies-${r.versionName}.apk")
        val conn = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 15000; readTimeout = 30000
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 }
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total != null) onProgress(read.toFloat() / total)
                    }
                }
            }
        } finally { conn.disconnect() }
        out
    }

    fun canInstall(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
