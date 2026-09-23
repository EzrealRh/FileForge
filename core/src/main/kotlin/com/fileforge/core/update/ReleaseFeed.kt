package com.fileforge.core.update

import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonException

class UpdateFeedException(message: String) : Exception(message)

/** 一个可下载的产物。[apiDownloadUrl] 是 api.github.com 的资产端点，不是 github.com 的下载链接。 */
class ReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long,
    val apiDownloadUrl: String,
    val ready: Boolean,
)

/** GitHub 上最新的那次发布，已经拆成界面要用的样子。 */
class RemoteRelease(
    val tagName: String,
    val version: SemanticVersion?,
    val notes: String,
    val publishedAt: String,
    val assets: List<ReleaseAsset>,
) {

    /** 优先名字里带 release 的 apk；退一步取最小的 apk，免得把 25MB 的 debug 包当更新下发。 */
    fun pickApk(): ReleaseAsset? = assets
        .filter { it.ready && it.name.endsWith(".apk", ignoreCase = true) }
        .minWithOrNull(
            compareBy<ReleaseAsset> { if (it.name.contains("release", ignoreCase = true)) 0 else 1 }
                .thenBy { it.size },
        )

    /** 两边版本号都读得出来才敢说有没有新版，读不出来就如实说不知道。 */
    fun isNewerThan(local: SemanticVersion?): Boolean {
        val remote = version ?: return false
        return local != null && remote > local
    }

    /** 发布日期只到天上，界面只留到天。 */
    val dayLabel: String get() = publishedAt.substringBefore('T')
}

object ReleaseFeed {

    const val OWNER = "EzrealRh"
    const val REPO = "FileForge"

    /**
     * 一律走 api.github.com：这类校园网会把 github.com:443 掐掉，
     * 但同一个资产用 API 端点带 `Accept: application/octet-stream` 能直接下下来（PC 上实测过）。
     */
    const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    fun parse(body: String): RemoteRelease {
        val root = try {
            Json.parse(body)
        } catch (error: JsonException) {
            throw UpdateFeedException("更新服务器回的东西读不懂")
        }
        if (root.string("tag_name") == null) {
            val message = root.string("message") ?: "回包里没有版本信息"
            throw UpdateFeedException(
                when {
                    message.contains("Not Found", ignoreCase = true) ->
                        "找不到发布页：仓库是私有的，要么在下面填一个只读 token，要么把仓库转公开"
                    message.contains("credentials", ignoreCase = true) || message.contains("token", ignoreCase = true) ->
                        "token 不被接受，重新生成一个只有读取权限的再填"
                    else -> "GitHub 说：$message"
                },
            )
        }
        val assets = root.list("assets").mapNotNull { asset ->
            val name = asset.string("name") ?: return@mapNotNull null
            val url = asset.string("url") ?: return@mapNotNull null
            ReleaseAsset(
                id = asset.long("id") ?: 0L,
                name = name,
                size = asset.long("size") ?: 0L,
                apiDownloadUrl = url,
                ready = (asset.string("state") ?: "uploaded") == "uploaded",
            )
        }
        return RemoteRelease(
            tagName = root.string("tag_name") ?: "",
            version = SemanticVersion.parse(root.string("tag_name")),
            notes = root.string("body") ?: "",
            publishedAt = root.string("published_at") ?: "",
            assets = assets,
        )
    }
}
