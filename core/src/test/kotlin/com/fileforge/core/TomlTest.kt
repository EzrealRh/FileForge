package com.fileforge.core

import com.fileforge.core.data.Toml
import com.fileforge.core.data.TomlException
import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonArray
import com.fileforge.core.json.JsonRender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class TomlTest {

    private val sample = """
        title = "TOML 示例"
        count = 42
        pi = 3.14
        enabled = true
        tags = ["a", "b", "c"]
        born = 1979-05-27T07:32:00Z

        [owner]
        name = "Tom"
        nick = 'T'

        [database]
        ports = [8000, 8001]
        limits = { max = 10, min = 1 }

        [servers.alpha]
        ip = "10.0.0.1"

        [[products]]
        name = "Hammer"
        sku = 738594937

        [[products]]
        name = "Nail"
    """.trimIndent()

    @Test
    fun `读表、数组、内联表与数组表`() {
        val tree = Toml.parse(sample)
        assertEquals("TOML 示例", tree.string("title"))
        assertEquals("42", tree.field("count")?.numberText)
        assertEquals("3.14", tree.field("pi")?.numberText)
        assertEquals(true, tree.field("enabled")?.boolValue)
        assertEquals(3, tree.field("tags")?.arrayValue?.size)
        assertEquals("1979-05-27T07:32:00Z", tree.field("born")?.stringValue)

        val owner = tree.field("owner")!!
        assertEquals("Tom", owner.string("name"))
        assertEquals("T", owner.string("nick"))

        val limits = tree.field("database")!!.field("limits")!!
        assertEquals("10", limits.field("max")?.numberText)
        assertEquals("1", limits.field("min")?.numberText)

        val alpha = tree.field("servers")!!.field("alpha")!!
        assertEquals("10.0.0.1", alpha.string("ip"))

        val products = tree.field("products")?.arrayValue ?: emptyList()
        assertEquals(2, products.size)
        assertEquals("Hammer", products[0].string("name"))
        assertEquals("738594937", products[0].field("sku")?.numberText)
        assertEquals("Nail", products[1].string("name"))
    }

    @Test
    fun `写出去再读回来是同一棵树`() {
        val tree = Toml.parse(sample)
        val written = Toml.write(tree)
        val again = Toml.parse(written)
        assertEquals(JsonRender.render(tree), JsonRender.render(again)) { written }

        // 落盘给 tools/verify_toml.py 用第三方解析器对证
        val out = File("build/toml").apply { mkdirs() }
        out.resolve("sample.toml").writeText(sample)
        out.resolve("from-toml.json").writeText(JsonRender.render(tree))
        out.resolve("out.toml").writeText(written)
        out.resolve("roundtrip.json").writeText(JsonRender.render(again))
    }

    @Test
    fun `点号键与引号键`() {
        val tree = Toml.parse("a.b.c = 1\n\"x.y\" = 2\n")
        val a = tree.field("a")!!.field("b")!!
        assertEquals("1", a.field("c")?.numberText)
        assertEquals("2", tree.field("x.y")?.numberText)
    }

    @Test
    fun `日期时间保持原文、不受本地时区影响`() {
        val tree = Toml.parse("d = 2024-02-29\nlocal = 07:32:00.123\n")
        assertEquals("2024-02-29", tree.string("d"))
        assertEquals("07:32:00.123", tree.string("local"))
    }

    @Test
    fun `写错的 TOML 逐条拒绝`() {
        assertThrows(TomlException::class.java) { Toml.parse("a = 1\na = 2\n") }
        assertThrows(TomlException::class.java) { Toml.parse("a = 012\n") }
        assertThrows(TomlException::class.java) { Toml.parse("a 1\n") }
        assertThrows(TomlException::class.java) { Toml.parse("a = [1, 2\n") }
        assertThrows(TomlException::class.java) { Toml.parse("[a]\n[a]\n") }
        assertThrows(TomlException::class.java) { Toml.parse("a = \"x\n") }
    }

    @Test
    fun `空表与空数组能往返`() {
        val tree = Toml.parse("[a]\n\nb = []\n")
        val text = Toml.write(tree)
        assertTrue(text.contains("[a]"), text)
        assertEquals(0, Toml.parse(text).field("a")?.field("b")?.arrayValue?.size)

        val empty = Toml.parse("[empty]\n")
        assertEquals(0, empty.field("empty")?.members?.size)
        assertTrue(Toml.write(empty).contains("[empty]"))
    }
}
