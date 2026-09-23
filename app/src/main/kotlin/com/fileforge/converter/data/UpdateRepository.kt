package com.fileforge.converter.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.fileforge.core.update.ReleaseAsset
import com.fileforge.core.update.ReleaseFeed
import com.fileforge.core.update.RemoteRelease
import com.fileforge.core.update.SemanticVersion
import com.fileforge.core.update.UpdateFeedException
import com.fileforge.core.util.SizeInput
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用内更新：问 GitHub 要最新 release，把 apk 下到应用自己的缓存目录，再交给系统安装器。
 *
 * 只往 api.github.com 发两个请求（查版本、下安装包），装什么由用户在本机点确认。
 * 仓库公开时不用任何凭据；token 输入框留给以后改私有或换源的情况，只存本机 SharedPreferences。
 */
class UpdateRepository(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("update", Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    val hasToken: Boolean get() = token.isNotBlank()

    fun localVersionName(): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "未知"

    fun localVersion(): SemanticVersion? = SemanticVersion.parse(localVersionName())

    suspend fun check(): RemoteRelease = withContext(Dispatchers.IO) {
        val connection = followed(ReleaseFeed.LATEST_URL, "application/vnd.github+json")
        val status = connection.responseCode
        val body = runCatching {
            (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        }.getOrDefault("")
        runCatching { connection.disconnect() }
        // 404/401 的正文里有 GitHub 给的原因，交给 ReleaseFeed 翻成中文
        if (body.isBlank()) throw UpdateFeedException("GitHub 没回内容（HTTP $status，多半是网络没放通）")
        ReleaseFeed.parse(body)
    }

    /**
     * 下到 `cacheDir/apk/`。文件名用资产 id 自己拼，不拿服务器给的名字当路径，
     * 免得远端一个 `../` 就写到别处去。
     */
    suspend fun download(asset: ReleaseAsset, onProgress: (percent: Int, done: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(app.cacheDir, APK_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { stale -> runCatching { stale.delete() } }
            val target = File(dir, "update-${asset.id}.apk")

            val connection = followed(asset.apiDownloadUrl, "application/octet-stream")
            var written = 0L
            var lastPercent = -1
            try {
                val status = connection.responseCode
                require(status in 200..299) {
                    "下载没开始：${connection.url.host} 返回 HTTP $status（这个域名可能没被网络放通）"
                }
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            val percent = if (asset.size > 0) (written * 100 / asset.size).toInt() else 0
                            if (percent / 2 != lastPercent / 2) {
                                lastPercent = percent
                                onProgress(percent, written, asset.size)
                            }
                        }
                    }
                }
            } finally {
                runCatching { connection.disconnect() }
            }

            if (asset.size > 0 && written != asset.size) {
                runCatching { target.delete() }
                throw UpdateFeedException(
                    "下载不完整：期望 ${SizeInput.format(asset.size)}，实际 ${SizeInput.format(written)}，已经丢掉重下",
                )
            }
            onProgress(100, written, asset.size)
            target
        }

    /** 系统安装器只认 content://，物理路径它拿不到。 */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Android 8 起，装安装包要用户先允许"安装未知应用"。 */
    fun needsInstallPermission(): Boolean =
        runCatching { !app.packageManager.canRequestPackageInstalls() }.getOrDefault(true)

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun open(endpoint: String, accept: String, withAuth: Boolean = true): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // GitHub API 没有 User-Agent 直接 403，这个头必须带
            setRequestProperty("User-Agent", "FileForge-Android")
            setRequestProperty("Accept", accept)
            if (withAuth && hasToken) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15_000
            readTimeout = 30_000
            // 重定向自己跟：第二跳是另一个域名（release-assets.githubusercontent.com），
            // 有些网络环境只放通 api.github.com，所以错误里要说清楚是哪个域名被掐
            instanceFollowRedirects = false
        }

    /** 跟着 302 走到真正存包的地址，最多三跳；状态码留给调用方判断（404 的正文里有原因）。 */
    private fun followed(endpoint: String, accept: String): HttpURLConnection {
        var connection = open(endpoint, accept)
        var hops = 0
        while (hops < MAX_HOPS) {
            val status = connection.responseCode
            val location = connection.getHeaderField("Location")
            if (status !in 300..399 || location.isNullOrBlank()) return connection
            hops++
            runCatching { connection.disconnect() }
            // 带签名的临时地址不需要 token，也不能再带
            connection = open(location, accept, withAuth = false)
        }
        return connection
    }

    private companion object {
        const val APK_DIR = "apk"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val MAX_HOPS = 3
    }
}
