package com.fileforge.core.update

import java.io.File
import java.security.MessageDigest

/**
 * 发布说明里自带的 SHA-256 摘要（发版流程约定：说明里写一行 `SHA256: <64 位十六进制>`，
 * 或逐个资产写 `<文件名> <64 位十六进制>`）。
 *
 * 应用内更新整条链路都是 HTTPS，但 HTTPS 保证的是"通道"，不是"这份 APK 就是作者编译的那份"——
 * 发布账号一旦被动，安装器只校验签名一致。有了摘要这一道，说明与包对不上时宁可不下。
 * 说明里没写摘要就不勉强：没有据可查，硬编一个才是自欺。
 */
object AssetDigest {

    private val HEX64 = Regex("""[0-9a-fA-F]{64}""")

    /** 从发布说明里找出 [assetName] 对应的摘要；全篇只有一个摘要时也算数。 */
    fun fromNotes(notes: String, assetName: String): String? {
        val named = notes.lineSequence()
            .filter { it.contains(assetName, ignoreCase = true) }
            .mapNotNull { HEX64.find(it)?.value }
            .firstOrNull()
        if (named != null) return named.lowercase()
        val all = HEX64.findAll(notes).map { it.value.lowercase() }.toSet()
        return if (all.size == 1) all.single() else null
    }

    /** 文件的 SHA-256 与摘要是否一致；摘要长得不对就按"没有"处理，返回 false。 */
    fun matches(file: File, digest: String?): Boolean {
        val expected = digest?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            ?: return false
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } == expected
    }
}
