package com.fileforge.core

import com.fileforge.core.update.AssetDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

class AssetDigestTest {

    private val hexA = "a".repeat(64)
    private val hexB = "b".repeat(64)

    @Test
    fun `说明里按文件名给的摘要优先`() {
        val notes = "更新内容：修了几个 bug\n- FileForge-release.apk $hexA\n- FileForge-debug.apk $hexB"
        assertEquals(hexA, AssetDigest.fromNotes(notes, "FileForge-release.apk"))
        assertEquals(hexB, AssetDigest.fromNotes(notes, "FileForge-debug.apk"))
    }

    @Test
    fun `全篇只有一个摘要时也算数`() {
        assertEquals(hexA, AssetDigest.fromNotes("SHA256: $hexA", "任意名字.apk"))
        assertEquals(hexA, AssetDigest.fromNotes("SHA256:${hexA.uppercase()}", "x.apk"), "大小写归一")
    }

    @Test
    fun `多个摘要又对不上文件名就宁缺毋滥`() {
        val notes = "SHA256: $hexA\n另一个 $hexB"
        assertNull(AssetDigest.fromNotes(notes, "other.apk"), "指认不清就不校验，也别拿错的那份")
        assertNull(AssetDigest.fromNotes("", "x.apk"), "说明里没写摘要就不勉强")
    }

    @Test
    fun `文件摘要对得上才算过`(@TempDir dir: File) {
        val content = "这是安装包的内容，随便一些字节 123".toByteArray(Charsets.UTF_8)
        val file = File(dir, "update.apk").apply { writeBytes(content) }
        val real = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it) }
        assertTrue(AssetDigest.matches(file, real), "真摘要要对上")
        assertTrue(AssetDigest.matches(file, real.uppercase()), "大小写无关")
        assertFalse(AssetDigest.matches(file, "f".repeat(64)), "对不上的包不能放行")
        assertFalse(AssetDigest.matches(file, null), "没给摘要就不放行")
        assertFalse(AssetDigest.matches(file, "不是十六进制"), "长得不对的摘要按没有处理")
    }
}
