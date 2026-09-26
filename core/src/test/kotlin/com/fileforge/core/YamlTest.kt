package com.fileforge.core

import com.fileforge.core.data.Yaml
import com.fileforge.core.data.YamlException
import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonNull
import com.fileforge.core.json.JsonObject
import com.fileforge.core.json.JsonRender
import com.fileforge.core.json.JsonString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * YAML 子集的读与写。
 *
 * 判据分两层：这一层钉"形状与类型"（块、序列、行内、块标量、注释、锚点、转义），
 * 第三方那一层（`tools/verify_yaml.py`）拿 PyYAML 复核同一批文件 —— 两边判据不同的地方
 * 是**声明过的差异**（`yes` 与六十进制那类），在本层各有一条断言钉着，不让它漂成随机行为。
 */
class YamlTest {

    private fun parse(text: String): Json = Yaml.parse(text)

    private fun json(text: String): String = JsonRender.render(parse(text))

    // ---- 块结构 -----------------------------------------------------------

    @Test
    fun `两层嵌套的映射`() {
        assertEquals("""{"server":{"host":"localhost","port":8080}}""",
            json("server:\n  host: localhost\n  port: 8080"))
    }

    @Test
    fun `列表不额外缩进也算这一项的`() {
        assertEquals("""{"items":["甲","乙"]}""", json("items:\n- 甲\n- 乙"))
        assertEquals("""{"items":["甲","乙"]}""", json("items:\n  - 甲\n  - 乙"))
    }

    @Test
    fun `列表项里直接写键值对`() {
        assertEquals("""[{"name":"甲","n":1},{"name":"乙","n":2}]""",
            json("- name: 甲\n  n: 1\n- name: 乙\n  n: 2"))
    }

    @Test
    fun `列表项下面是整块映射`() {
        assertEquals("""[{"k":{"deep":"v"}}]""", json("-\n  k:\n    deep: v"))
    }

    @Test
    fun `三层嵌套`() {
        assertEquals("""{"a":{"b":{"c":[1,2]}}}""", json("a:\n  b:\n    c:\n    - 1\n    - 2"))
    }

    @Test
    fun `键或列表后面什么都没有是给 null 不是空字符串`() {
        assertTrue(parse("a:\nb: 2").field("a") === JsonNull)
        assertEquals("""{"a":null,"b":2}""", json("a:\nb: 2"))
    }

    @Test
    fun `行内列表与字典`() {
        assertEquals("""{"list":[1,2,3]}""", json("list: [1, 2, 3]"))
        assertEquals("""{"map":{"a":1,"b":"两"}}""", json("map: {a: 1, b: 两}"))
        assertEquals("""{"nest":[{"x":[]},{}]}""", json("nest: [{x: []}, {}]"))
    }

    // ---- 标量类型 ----------------------------------------------------------

    @Test
    fun `数字的写法收敛成 JSON 认的样子`() {
        // 01 → 1、+1 → 1、1_000 → 1000、.5 → 0.5；1.50 的**写法**保留（那是别人写的有效数字）
        assertEquals("""{"a":1,"b":1,"c":1000,"d":0.5,"e":1.50,"f":31,"g":15,"h":-2,"i":1}""",
            json("a: 1\nb: 01\nc: 1_000\nd: .5\ne: 1.50\nf: 0x1f\ng: 0o17\nh: -2\ni: +1"))
    }

    @Test
    fun `科学记数法要带小数点才算数 1e3 保持文字`() {
        assertEquals("""{"a":1.5e3,"b":"1e3"}""", json("a: 1.5e3\nb: 1e3"))
    }

    @Test
    fun `1_2 核心模式里 yes 与 on 还是文字`() {
        // PyYAML 的 1.1 会把它们变成 true —— 这是声明过的差异，判据脚本里逐条钉着
        assertEquals("""{"a":"yes","b":"no","c":"on","d":"off","e":"~x"}""",
            json("a: yes\nb: no\nc: on\nd: off\ne: ~x"))
    }

    @Test
    fun `六十进制保持文字 不变成秒数`() {
        assertEquals("""{"version":"1:30","t":"1:2:3"}""", json("version: 1:30\nt: 1:2:3"))
    }

    @Test
    fun `日期与时间是文字 JSON 里没有那一型`() {
        assertEquals("""{"d":"2023-05-01","t":"12:00:00"}""", json("d: 2023-05-01\nt: 12:00:00"))
    }

    @Test
    fun `null 的四种写法与空值`() {
        listOf("a:", "a: ~", "a: null", "a: Null", "a: NULL").forEach {
            assertTrue(parse(it).field("a") === JsonNull, it)
        }
    }

    @Test
    fun `无穷与非数留成文字因为 JSON 装不下`() {
        assertEquals("""{"a":".inf","b":".nan"}""", json("a: .inf\nb: .nan"))
    }

    // ---- 引号、注释、块标量、锚点 ------------------------------------------------

    @Test
    fun `两种引号与各自的转义`() {
        val text = "a: 'it''s'\n" +
            "b: \"line\\nbreak\"\n" +
            "c: '#1'\n" +
            "d: \"tab\\there\"\n"
        assertEquals("""{"a":"it's","b":"line\nbreak","c":"#1","d":"tab\there"}""", json(text))
    }

    @Test
    fun `井号只有前面是空白时才是注释`() {
        assertEquals("""{"url":"http://a#b","note":"甲"}""", json("url: http://a#b\nnote: 甲  # 乙"))
        assertEquals("""{"only":"甲"}""", json("# 整行注释\nonly: 甲"))
    }

    @Test
    fun `块标量保留换行与里面的假结构`() {
        val parsed = parse("log: |\n  first\n  - 不像列表\n  # 不像注释\nsecond: 2")
        assertEquals("first\n- 不像列表\n# 不像注释\n", parsed.string("log"))
        assertEquals(2.0, parsed.field("second")?.numberValue)
    }

    @Test
    fun `块标量的连字符形式不留尾换行`() {
        assertEquals("甲\n乙", parse("t: |-\n  甲\n  乙").string("t"))
    }

    @Test
    fun `折叠块标量把相邻行并成一行`() {
        assertEquals("甲 乙\n丙", parse("t: >-\n  甲\n  乙\n\n  丙").string("t"))
    }

    @Test
    fun `锚点与别名 加合并键`() {
        val text = "base: &b\n  a: 1\n  c: 3\nuse:\n  <<: *b\n  c: 9\n"
        val parsed = parse(text)
        assertEquals("""{"base":{"a":1,"c":3},"use":{"a":1,"c":9}}""", JsonRender.render(parsed))
    }

    @Test
    fun `别名的值直接复用`() {
        assertEquals("""{"one":[1,2],"two":[1,2]}""", json("one: &x [1, 2]\ntwo: *x"))
    }

    @Test
    fun `没定义过的别名要说出来 而不是当文字`() {
        val bad = assertThrows(YamlException::class.java) { parse("a: *nope") }
        assertTrue(bad.message!!.contains("没定义过"), bad.message)
    }

    @Test
    fun `标签与显式键直接拒 不硬读`() {
        assertThrows(YamlException::class.java) { parse("a: !!binary 3") }
        assertThrows(YamlException::class.java) { parse("? 复杂键\n: 值") }
    }

    @Test
    fun `缩进对不上时报行号`() {
        val bad = assertThrows(YamlException::class.java) { parse("a: 1\n    b: 2") }
        assertTrue(bad.message!!.contains("第 2 行"), bad.message)
    }

    @Test
    fun `tab 缩进直接拒`() {
        assertThrows(YamlException::class.java) { parse("a:\n\tb: 1") }
    }

    // ---- 写出侧 -------------------------------------------------------------

    @Test
    fun `写出后重读回来必须还是那棵树`() {
        val text = """
            |server:
            |  host: localhost
            |  port: 8080
            |  tags:
            |   - 甲
            |   - 乙
            |list:
            | - name: 一
            |   ok: true
            | - name: 二
            |   ok: false
            |empty: {}
            |nil: null
            |""".trimMargin()
        val tree = parse(text)
        assertEquals(JsonRender.render(tree), JsonRender.render(Yaml.parse(Yaml.write(tree))))
    }

    @Test
    fun `看着像别的类型的字符串一律加引号`() {
        // 少加一对引号，别人重读回去就不是那个值了
        val tricky = listOf("yes", "no", "on", "off", "1", "0755", "1.50", "2023-05-01", "1:30",
            "- 甲", "# 井", "a: b", "尾空格 ", "空", "true", "~", "1e3", ".inf", "带'引号'", "",
            "1.5e3", "0x1f", "2:01", "y", "N")
        tricky.forEach { value ->
            val written = Yaml.write(JsonObject(mapOf("k" to JsonString(value))))
            val back = Yaml.parse(written).string("k")
            assertEquals(value, back, "值「$value」写出去再读回来变了：$written")
        }
    }

    @Test
    fun `写出的缩进可以调`() {
        val tree = parse("a:\n  b:\n    c: 1")
        assertTrue(Yaml.write(tree, 4).contains("        c: 1"), Yaml.write(tree, 4))
    }

    @Test
    fun `六十进制每一位一个数字也要加引号`() {
        // PyYAML 1.1 会把 1:2:3 读成 3723 —— 少一层防就要靠外部判据才发现
        val written = Yaml.write(JsonObject(mapOf("t" to JsonString("1:2:3"))))
        assertTrue(written.contains("'1:2:3'"), written)
        assertEquals("1:2:3", Yaml.parse(written).string("t"))
    }

    @Test
    fun `像不像 YAML 判得住散文与配置`() {
        assertTrue(Yaml.looksLikeYaml("a: 1\nb:\n  - 甲"))
        assertTrue(Yaml.looksLikeYaml("- 甲\n- 乙"))
        assertTrue(!Yaml.looksLikeYaml("今天天气不错，我们去公园。"))
        assertTrue(!Yaml.looksLikeYaml("{\"a\": 1}"))
    }
}
