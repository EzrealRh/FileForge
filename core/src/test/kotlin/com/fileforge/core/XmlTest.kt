package com.fileforge.core

import com.fileforge.core.data.Xml
import com.fileforge.core.data.XmlException
import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonArray
import com.fileforge.core.json.JsonObject
import com.fileforge.core.json.JsonRender
import com.fileforge.core.json.JsonString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * XML ↔ JSON。
 *
 * 参照结构由 **Python 的 ElementTree** 走同一份字节得出（tools/make_xml_fixtures.py）：
 * 解析是它自己做的，只有那条映射约定共用 —— 而约定本身就是这里要判的东西。
 */
class XmlTest {

    private fun str(text: String) = JsonString(text)

    private fun obj(vararg pairs: Pair<String, Json>) = JsonObject(linkedMapOf(*pairs))

    private fun arr(vararg items: Json) = JsonArray(items.toList())

    private fun canonical(json: Json): String = JsonRender.render(json, sortKeys = true)

    private fun resourceText(name: String): String =
        javaClass.classLoader.getResourceAsStream("data/$name").use { input ->
            requireNotNull(input) { "缺少夹具 data/$name，先跑 python tools/make_xml_fixtures.py" }
            String(input.readBytes(), Charsets.UTF_8)
        }

    // ---- 解析：跟 ElementTree 对 ------------------------------------------------

    @Test
    fun `解析结果与 ElementTree 走出来的结构一致`() {
        listOf("book.xml", "order.xml").forEach { name ->
            val mine = Xml.parse(resourceText(name))
            val theirs = Json.parse(resourceText("$name.truth"))
            assertEquals(canonical(theirs), canonical(mine), "$name 的解析结果与 Python 走出来的不一样")
        }
    }

    @Test
    fun `约定要能被看见`() {
        val order = Xml.parse(resourceText("order.xml")).field("order")!!
        // 子元素一律成数组，哪怕只有一个：这样"有几个孩子"这个信息不会丢
        assertTrue(order.field("total") is JsonArray, "单个子元素也得是数组")
        assertEquals(1, order.field("total")?.arrayValue?.size)
        // 属性加 @，元素自己的文字进 #text
        val total = order.field("total")!!.arrayValue.first()
        assertEquals("CNY", total.field("@currency")?.stringValue)
        assertEquals("19.50", total.field("#text")?.stringValue, "数字写法不许被改写")
        // 空元素是数组里的一个 null，而不是"整个键没了"
        assertTrue(order.members.containsKey("remark"), "空元素不该凭空消失")
        assertEquals(1, order.field("remark")?.arrayValue?.size)
        assertTrue(order.field("remark")!!.arrayValue.first().isNull)
    }

    @Test
    fun `注释与处理指令按约定丢掉`() {
        assertTrue("注释" !in canonical(Xml.parse(resourceText("book.xml"))), "注释没丢掉")
        // 根元素只有文字时直接就是那个字符串：它不是谁的子元素，所以不套数组
        assertEquals("""{"a":"1"}""", JsonRender.render(Xml.parse("<?xml version=\"1.0\"?><?pi?>\n<a>1</a>")))
    }

    @Test
    fun `实体与 CDATA 要还原成真实字符`() {
        val book = Xml.parse(resourceText("book.xml")).field("book")!!
        assertEquals("实体 & 与 <尖括号>", book.field("note")!!.arrayValue.first().stringValue)
        val first = book.field("chapter")!!.arrayValue.first()
        assertEquals("里面可以有 <标签> 与 & 号", first.field("body")!!.arrayValue.first().stringValue)
    }

    @Test
    fun `混合内容把元素自己的文字放进 #-text`() {
        val title = Xml.parse(resourceText("book.xml")).field("book")!!.field("title")!!.arrayValue.first()
        assertEquals("带子元素的标题", title.field("#text")?.stringValue)
        assertEquals("内嵌", title.field("n")!!.arrayValue.first().stringValue)
    }

    // ---- 拒绝：坏输入与安全 ------------------------------------------------------

    @Test
    fun `不是合法 XML 就直说`() {
        listOf("", "这根本不是标记语言", "<a>", "<a><b></a></b>", "<r></r><other/>").forEach { bad ->
            val error = runCatching { Xml.parse(bad) }.exceptionOrNull()
            assertNotNull(error, "$bad 该被拒")
            assertTrue(
                error is XmlException && error.message!!.contains("合法"),
                "$bad 抛的是 ${error!!.javaClass.simpleName}（${error.message}），要的是能念给用户听的错误",
            )
        }
    }

    @Test
    fun `带 DTD 或实体定义的一律不解析`() {
        // 这是安全判据不是格式偏好：外部实体能让一个"本地转换工具"去访问声明里写的地址（XXE），
        // 内部实体可以无限展开把内存吃光
        val xxe = "<?xml version=\"1.0\"?>\n" +
            "<!DOCTYPE r [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n<r>&xxe;</r>"
        val bomb = "<?xml version=\"1.0\"?>\n<!DOCTYPE r [<!ENTITY a \"x\">]>\n<r>&a;</r>"
        listOf(xxe, bomb, "<!DOCTYPE html>\n<html/>").forEach { input ->
            val error = runCatching { Xml.parse(input) }.exceptionOrNull()
            assertNotNull(error, "带 DTD 的必须拒：${input.take(40)}")
            assertTrue(
                error!!.message!!.contains("DTD") || error.message!!.contains("实体"),
                "要报的是安全理由，实际是：${error.message}",
            )
        }
    }

    @Test
    fun `未声明的前缀按字面留在名字里`() {
        // 刻意不感知命名空间：前缀原样留着，xmlns 声明当普通属性。
        // 严格解析器（ElementTree）会判未绑定前缀非法 —— 这条差异是约定的结果，
        // 所以在这里单独钉住，不塞进 Python 参照值里混淆判据
        val a = Xml.parse("<a xmlns:ns=\"urn:x\"><ns:b>1</ns:b></a>").field("a")!!
        assertNotNull(a.field("@xmlns:ns"), "xmlns 声明按普通属性留着")
        assertEquals("1", a.field("ns:b")!!.arrayValue.first().stringValue)
    }

    // ---- 渲染 -----------------------------------------------------------------

    @Test
    fun `渲染保留结构并写出可读的 XML`() {
        val xml = Xml.render(obj("order" to Xml.parse(resourceText("order.xml")).field("order")!!))
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), xml.take(60))
        assertTrue("<item sku=\"A1\">" in xml, xml)
        assertTrue("<price>12.50</price>" in xml, "数字写法不该在渲染时被改：$xml")
        assertTrue("<remark/>" in xml, "空元素要写成自闭合：$xml")
        assertTrue("<total currency=\"CNY\">" in xml, xml)
    }

    @Test
    fun `转义覆盖文字与属性`() {
        val xml = Xml.render(
            obj(
                "doc" to obj(
                    "@note" to str("他说\"引号\" & <尖括号>"),
                    "body" to arr(str("A & B < C")),
                ),
            ),
        )
        assertTrue("&amp;" in xml && "&lt;" in xml, xml)
        assertTrue("&quot;" in xml, "属性里的引号必须转：$xml")
    }

    @Test
    fun `转一圈回去文字与属性都还在`() {
        val original = obj("r" to obj("@k" to str("a&b"), "t" to arr(str("含 < 与 & 的正文"))))
        val back = Xml.parse(Xml.render(original))
        assertEquals("a&b", back.field("r")!!.field("@k")?.stringValue)
        assertEquals("含 < 与 & 的正文", back.field("r")!!.field("t")!!.arrayValue.first().stringValue)
    }

    @Test
    fun `不能当标签名的键要报错而不是悄悄改名`() {
        // 静悄悄把 `a b` 改成 `a_b` 等于给用户一份他自己没写过的文件
        listOf("1bad", "", "a b", "a<b", "-dash", "a/b").forEach { key ->
            val error = runCatching { Xml.render(obj(key to arr(str("v")))) }.exceptionOrNull()
            assertNotNull(error, "键「$key」不能当标签名，必须报错而不是改写成别的")
        }
    }

    @Test
    fun `中文元素名是合法的，不许当成坏名字拒掉`() {
        // XML 的 Name 规则允许 Unicode 字母，中文用户的文件里真会有中文标签
        val xml = Xml.render(obj("清单" to arr(obj("名称" to arr(str("甲"))))), indent = 0)
        assertTrue("<清单><名称>甲</名称></清单>" in xml, xml)
        // 只有子元素套数组，根元素自己不套 —— 与 Python 参照值同一条约定
        assertEquals("甲", Xml.parse(xml).field("清单")!!.field("名称")!!.arrayValue.first().stringValue)
    }

    @Test
    fun `混合内容转一圈不许丢掉元素自己的文字`() {
        // 文字要是写在孩子后面，它就变成上一个孩子的 tail，再解析回来这个元素的正文就没了 ——
        // 那是丢数据，不能拿"顺序不保留"当借口
        val original = obj("t" to obj("#text" to str("正文在前"), "n" to arr(str("内嵌"))))
        val xml = Xml.render(original, indent = 0)
        assertTrue("<t>正文在前<n>内嵌</n></t>" == xml.substringAfter("?>").trim(), xml)
        val back = Xml.parse(xml).field("t")!!
        assertEquals("正文在前", back.field("#text")?.stringValue)
        assertEquals("内嵌", back.field("n")!!.arrayValue.first().stringValue)
    }

    @Test
    fun `空数组的字段不许凭空消失`() {
        val xml = Xml.render(obj("r" to obj("list" to arr())))
        assertTrue("<list/>" in xml, "空数组至少留一个空元素：$xml")
    }

    @Test
    fun `产物落盘交给 ElementTree 复核`() {
        val dir = java.io.File("build/data").apply { mkdirs() }
        val order = Xml.parse(resourceText("order.xml"))
        val book = Xml.parse(resourceText("book.xml"))
        java.io.File(dir, "order.round.xml").writeText(Xml.render(order))
        java.io.File(dir, "book.round.xml").writeText(Xml.render(book))
        java.io.File(dir, "order.parsed.json").writeText(JsonRender.render(order))
        java.io.File(dir, "book.parsed.json").writeText(JsonRender.render(book))
        assertTrue(java.io.File(dir, "order.round.xml").length() > 0)
    }
}
