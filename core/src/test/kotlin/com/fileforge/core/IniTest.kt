package com.fileforge.core

import com.fileforge.core.data.Ini
import com.fileforge.core.data.IniException
import com.fileforge.core.json.JsonRender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class IniTest {

    private val sample = """
        ; 顶部注释
        [app]
        app_name = 文件工坊
        version = 1.2.3

        [server]
        host = 127.0.0.1
        port = 8080
        enabled = true

        [paths]
        output = /storage/emulated/0/文件工坊
        ; 这一行是注释
        backup: /sdcard/backup
    """.trimIndent()

    @Test
    fun `分节与键值、注释`() {
        val tree = Ini.parse(sample)
        val app = tree.field("app")!!
        assertEquals("文件工坊", app.string("app_name"))
        assertEquals("1.2.3", app.string("version"))
        val server = tree.field("server")!!
        assertEquals("127.0.0.1", server.string("host"))
        assertEquals("8080", server.string("port"))
        assertEquals("true", server.string("enabled"))
        assertEquals("/sdcard/backup", tree.field("paths")!!.string("backup"))
    }

    @Test
    fun `节之前的全局键放在空键里（configparser 不认这种写法，但 Windows INI 常见）`() {
        val tree = Ini.parse("app_name = 文件工坊\n[s]\nb = 2\n")
        assertEquals("文件工坊", tree.field("")?.string("app_name"))
        assertEquals("2", tree.field("s")?.string("b"))
        assertTrue(Ini.write(tree).startsWith("app_name="))
    }

    @Test
    fun `写出去再读回来是同一棵树`() {
        val tree = Ini.parse(sample)
        val written = Ini.write(tree)
        val again = Ini.parse(written)
        assertEquals(JsonRender.render(tree), JsonRender.render(again)) { written }

        val out = File("build/ini").apply { mkdirs() }
        out.resolve("sample.ini").writeText(sample)
        out.resolve("from-ini.json").writeText(JsonRender.render(tree))
        out.resolve("out.ini").writeText(written)
        out.resolve("roundtrip.json").writeText(JsonRender.render(again))
    }

    @Test
    fun `重复键后写的赢`() {
        val tree = Ini.parse("[s]\na = 1\na = 2\n")
        assertEquals("2", tree.field("s")!!.string("a"))
    }

    @Test
    fun `写坏的 INI 与摆不下的形状都拒绝`() {
        assertThrows(IniException::class.java) { Ini.parse("just some words\n") }
        assertThrows(IniException::class.java) { Ini.parse("[broken\n") }
        val tree = Ini.parse("[s]\na = 1\n")
        val withArray = com.fileforge.core.json.JsonObject(
            mapOf("s" to com.fileforge.core.json.JsonObject(
                mapOf("a" to com.fileforge.core.json.JsonArray(listOf(com.fileforge.core.json.JsonString("x")))),
            )),
        )
        assertThrows(IniException::class.java) { Ini.write(withArray) }
    }

    @Test
    fun `空节保留`() {
        val tree = Ini.parse("[empty]\n")
        val text = Ini.write(tree)
        assertTrue(text.contains("[empty]"), text)
        assertEquals(0, Ini.parse(text).field("empty")?.members?.size)
    }
}
