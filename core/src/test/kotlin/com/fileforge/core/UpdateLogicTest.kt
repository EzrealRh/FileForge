package com.fileforge.core

import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonException
import com.fileforge.core.update.ReleaseFeed
import com.fileforge.core.update.SemanticVersion
import com.fileforge.core.update.UpdateFeedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 线上 `releases/latest` 端点的真实响应，账号身份字段已换成 example-org 占位。 */
private fun fixture(name: String): String =
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("update/$name")) {
        "少了测试夹具 update/$name"
    }.readBytes().toString(Charsets.UTF_8)

class SemanticVersionTest {

    @Test
    fun `v 前缀和段数不同都能比`() {
        assertTrue(SemanticVersion.parse("v0.4.2")!! > SemanticVersion.parse("0.4.1")!!)
        assertEquals(0, SemanticVersion.parse("v0.4")!!.compareTo(SemanticVersion.parse("0.4.0")!!))
    }

    @Test
    fun `两位数段落不能按字符串比`() {
        assertTrue(SemanticVersion.parse("0.10.0")!! > SemanticVersion.parse("0.9.9")!!)
        assertTrue(SemanticVersion.parse("1.0.0")!! > SemanticVersion.parse("0.99.99")!!)
    }

    @Test
    fun `读不出的版本号一律给空`() {
        assertNull(SemanticVersion.parse("latest"))
        assertNull(SemanticVersion.parse("v0.4.2-beta"))
        assertNull(SemanticVersion.parse("0"))
        assertNull(SemanticVersion.parse(null))
        assertNull(SemanticVersion.parse(""))
    }
}

class ReleaseFeedTest {

    @Test
    fun `真实回包里能读出版本和两个包`() {
        val release = ReleaseFeed.parse(fixture("releases-latest.json"))
        assertEquals("v0.4.2", release.tagName)
        assertEquals(SemanticVersion.parse("0.4.2"), release.version)
        assertEquals(2, release.assets.size)
        assertEquals(7_665_282L, release.pickApk()?.size)
    }

    @Test
    fun `选包要拿 release 包而不是更大的 debug 包`() {
        val release = ReleaseFeed.parse(fixture("releases-latest.json"))
        assertEquals("fileforge-v0.4.2-release.apk", release.pickApk()?.name)
    }

    @Test
    fun `下载走 api 端点不是 github 域名`() {
        val apk = ReleaseFeed.parse(fixture("releases-latest.json")).pickApk()!!
        assertTrue(apk.apiDownloadUrl.startsWith("https://api.github.com/repos/"))
        assertTrue(apk.apiDownloadUrl.endsWith("/releases/assets/583416105"))
    }

    @Test
    fun `只有 debug 包时也能退一步拿最小包`() {
        val json = """{"tag_name":"v0.5.0","assets":[
            {"id":2,"name":"app-debug.apk","size":900,"state":"uploaded","url":"u2"},
            {"id":1,"name":"app-trace.apk","size":100,"state":"uploaded","url":"u1"},
            {"id":3,"name":"notes.txt","size":10,"state":"uploaded","url":"u3"}]}"""
        assertEquals("app-trace.apk", ReleaseFeed.parse(json).pickApk()?.name)
    }

    @Test
    fun `没上传完的包不算可下载`() {
        val json = """{"tag_name":"v0.5.0","assets":[
            {"id":1,"name":"a-release.apk","size":10,"state":"loading","url":"u"}]}"""
        assertNull(ReleaseFeed.parse(json).pickApk())
    }

    @Test
    fun `私有仓库的 404 会说清楚要 token`() {
        val error = assertThrows(UpdateFeedException::class.java) {
            ReleaseFeed.parse(fixture("not-found.json"))
        }
        assertTrue(error.message!!.contains("token"), error.message)
    }

    @Test
    fun `两边版本都读得出来才说有新版`() {
        val release = ReleaseFeed.parse(fixture("releases-latest.json"))
        assertTrue(release.isNewerThan(SemanticVersion.parse("0.4.1")))
        assertFalse(release.isNewerThan(SemanticVersion.parse("0.4.2")))
        assertFalse(release.isNewerThan(SemanticVersion.parse("0.5.0")))
        assertFalse(release.isNewerThan(null))
    }
}

class MiniJsonTest {

    @Test
    fun `嵌套对象数组和转义都能读`() {
        val root = Json.parse("""{"a":[1,2,{"b":"x\"y\n中"}],"c":true,"d":null,"e":"\u0041"}""")
        assertEquals(2L, root.list("a")[1].numberValue?.toLong())
        assertEquals("x\"y\n中", root.list("a")[2].string("b"))
        assertTrue(root.flag("c"))
        assertNull(root.field("d")?.stringValue)
        assertEquals("A", root.string("e"))
    }

    @Test
    fun `科学计数和负数也是数字`() {
        assertEquals(0.0004, Json.parse("""{"n":4e-4}""").field("n")?.numberValue!!, 1e-9)
        assertEquals(-12.5, Json.parse("""{"n":-12.5}""").field("n")?.numberValue!!, 1e-9)
    }

    @Test
    fun `坏输入一律抛错不返回半截结果`() {
        assertThrows(JsonException::class.java) { Json.parse("""{"a":1} trailing""") }
        assertThrows(JsonException::class.java) { Json.parse("""{"a":1,"b"}""") }
        assertThrows(JsonException::class.java) { Json.parse("""[1,2""") }
        assertThrows(JsonException::class.java) { Json.parse("""{"a":"未闭合""") }
        assertThrows(JsonException::class.java) { Json.parse("""{"a":tru}""") }
        assertThrows(JsonException::class.java) { Json.parse("""{"a":"\q"}""") }
    }
}
