package com.fileforge.core

import com.fileforge.core.data.Csv
import com.fileforge.core.data.Delimiter
import com.fileforge.core.data.TableBridge
import com.fileforge.core.data.Xml
import com.fileforge.core.data.Yaml
import com.fileforge.core.text.LineEnding
import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonRender
import com.fileforge.core.office.SheetToWrite
import com.fileforge.core.office.XlsxWrite
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 数据族补齐的那四条边（XML⇄YAML、CSV→XML、YAML→Excel）与已有的边**是不是同一套判断**。
 *
 * 这一族的卖点不是"能转"，而是"从哪条路转过去，得到的东西一样"：
 * XML→YAML 与 XML→JSON 必须是同一棵树；YAML→Excel 与 YAML→CSV 必须是同一张表；
 * 转出去的 XML 要能被自己读回来且一字不差（parse∘render 是个不动点）。
 * 每条都拿两份产物比，不看任何一条路的输出"看起来对"。
 *
 * 产物落到 `build/datamatrix/`，外部裁判（PyYAML / ElementTree / openpyxl / csv 模块）在
 * `tools/verify_data_matrix.py`。
 */
class DataMatrixTest {

    private fun resource(area: String, name: String): String {
        val input = javaClass.classLoader.getResourceAsStream("$area/$name")
        requireNotNull(input) { "缺少夹具 $area/$name" }
        return String(input.readBytes(), Charsets.UTF_8)
    }

    /** 按键排序后的 JSON 文本：比的是树的内容，不比键的书写顺序。 */
    private fun canonical(value: Json): String = JsonRender.render(value, sortKeys = true)

    private val catalog get() = Xml.parse(resource("data", "catalog.xml"))

    @Test
    fun `XML 转 YAML 与 XML 转 JSON 是同一棵树`() {
        val viaYaml = Yaml.parse(Yaml.write(catalog, 2))
        val viaJson = Json.parse(JsonRender.render(catalog, sortKeys = true))
        assertEquals(canonical(viaJson), canonical(viaYaml))
        assertEquals(canonical(catalog), canonical(viaYaml))
    }

    @Test
    fun `XML 经过 YAML 再转回来与直接转回来一字不差`() {
        val direct = Xml.render(catalog, root = "catalog")
        val throughYaml = Xml.render(Yaml.parse(Yaml.write(catalog, 2)), root = "catalog")
        assertEquals(direct, throughYaml)
    }

    @Test
    fun `写出去的 XML 自己读回来是同一个形状（parse 与 render 互为不动点）`() {
        val tree = Yaml.parse(resource("yaml", "config.yaml"))
        val once = Xml.render(tree, root = "config", item = "row")
        // 先钉住"每个键都在"：不动点这类断言只比"两次一样"，两次都少了同一个键它照样绿
        tree.members.keys.forEach { key ->
            assertTrue("<$key>" in once || "<$key/>" in once, "顶层的「$key」没进 XML：$once")
        }
        assertEquals(tree.members.size, Xml.parse(once).field("config")!!.members.size, "根下的子元素名数与顶层键数对不上")
        // 比的是**树**的不动点，不是字节的：读的一侧会把元素自己的文字首尾空白去掉
        // （块标量末尾那几个换行因此不保留），树本身稳定 —— 结构与值都在，逐条对上
        val fromXml = Xml.parse(once)
        val again = Xml.parse(Xml.render(fromXml, root = "config", item = "row"))
        assertEquals(canonical(fromXml), canonical(again))
        val fromCatalog = Xml.render(catalog, root = "catalog")
        assertEquals(fromCatalog, Xml.render(Xml.parse(fromCatalog), root = "catalog"))
    }

    @Test
    fun `CSV 转 XML 一行一个 row 且列名与格子原样`() {
        val text = resource("data", "table.csv")
        val doc = Csv.parse(text, Csv.detect(text))
        val columns = TableBridge.dedupe(doc.records.first())
        val xml = Xml.render(TableBridge.toRowsJson(doc, true, false), root = "table", item = "row")
        val back = Xml.parse(xml)
        val rows = back.field("table")!!.field("row")!!.arrayValue
        assertEquals(doc.records.size - 1, rows.size, "行数对不上")
        rows.forEachIndexed { index, row ->
            columns.forEach { column ->
                val want = doc.records[index + 1].getOrNull(columns.indexOf(column)) ?: ""
                assertEquals(
                    listOf(want),
                    row.field(column)?.arrayValue.orEmpty().map { it.stringValue.orEmpty() },
                    "第 ${index + 1} 行的「$column」不是原样",
                )
            }
        }
        // 没列名那面：列名写成 列1…列N，值还是原样，一个都不猜类型
        val headerless = Xml.render(
            com.fileforge.core.json.JsonArray(
                doc.records.map { record ->
                    val members = LinkedHashMap<String, Json>()
                    record.forEachIndexed { position, cell -> members["列${position + 1}"] = com.fileforge.core.json.JsonString(cell) }
                    com.fileforge.core.json.JsonObject(members)
                },
            ),
            root = "table", item = "row",
        )
        val headerRow = Xml.parse(headerless).field("table")!!.field("row")!!.arrayValue.first()
        assertEquals((1..doc.widest).map { "列$it" }, headerRow.members.keys.toList(), "没列名时该写成 列1…列N")
        assertEquals(
            doc.records.first(),
            (1..doc.widest).map { headerRow.field("列$it")!!.arrayValue.first().stringValue.orEmpty() },
            "值要原样在",
        )
    }

    @Test
    fun `YAML 转 Excel 与 YAML 转 CSV 是同一张表`() {
        val text = resource("data", "table.csv")
        val doc = Csv.parse(text, Csv.detect(text))
        val tree = TableBridge.toRowsJson(doc, true, false)
        val table = TableBridge.toTable(tree) ?: error("这份 YAML 形状摊不成表")
        val throughCsv = Csv.parse(
            Csv.render(table.records, Delimiter.Comma, LineEnding.Lf, false),
            Delimiter.Comma,
        )
        assertEquals(table.records, throughCsv.records)
        val workbook = XlsxWrite.workbook(listOf(SheetToWrite("table", table.records)))
        assertTrue(workbook.bytes.size > 1000, "工作簿太小了：${workbook.bytes.size}")
        assertEquals(table.columns.size, table.records.first().size)
    }

    @Test
    fun `难写的值转过去还是那个写法`() {
        // 走两圈树：带引号也好、块式也好，读回来必须还是那些字 —— 这比"看着像加了引号"要硬
        val text = resource("data", "tricky.csv")
        val doc = Csv.parse(text, Csv.detect(text))
        val tree = TableBridge.toRowsJson(doc, true, false)
        assertEquals(canonical(tree), canonical(Yaml.parse(Yaml.write(tree, 2))))
        // XML 里没有类型这回事：值原样进元素，读回来还是那个写法
        val tableText = resource("data", "table.csv")
        val table = Csv.parse(tableText, Csv.detect(tableText))
        val xml = Xml.render(TableBridge.toRowsJson(table, true, false), root = "t", item = "row")
        listOf("007", "1.50", "true", "false", "0.25").forEach { raw ->
            assertTrue(">$raw<" in xml, "$raw 该原样写在元素里：$xml")
        }
        val back = Xml.parse(xml)
        val firstRow = back.field("t")!!.field("row")!!.arrayValue.first()
        assertEquals("007", firstRow.field("编号")!!.arrayValue.first().stringValue)
        assertEquals("1.50", firstRow.field("比值")!!.arrayValue.first().stringValue)
    }

    @Test
    fun `产物落到 build 供外部判据对照`() {
        val dir = File("build/datamatrix").apply { mkdirs() }
        val catalogTree = catalog
        File(dir, "catalog.yaml").writeText(Yaml.write(catalogTree, 2), Charsets.UTF_8)
        File(dir, "catalog.json").writeText(JsonRender.render(catalogTree), Charsets.UTF_8)
        File(dir, "catalog.xml").writeText(Xml.render(catalogTree, root = "catalog"), Charsets.UTF_8)
        val config = Yaml.parse(resource("yaml", "config.yaml"))
        File(dir, "config.xml").writeText(Xml.render(config, root = "config", item = "row"), Charsets.UTF_8)
        val tableText = resource("data", "table.csv")
        val tableDoc = Csv.parse(tableText, Csv.detect(tableText))
        val rows = TableBridge.toRowsJson(tableDoc, true, false)
        File(dir, "table.xml").writeText(Xml.render(rows, root = "table", item = "row"), Charsets.UTF_8)
        val tricky = resource("data", "tricky.csv")
        val trickyDoc = Csv.parse(tricky, Csv.detect(tricky))
        File(dir, "tricky.yaml").writeText(Yaml.write(TableBridge.toRowsJson(trickyDoc, true, false), 2), Charsets.UTF_8)
        val table = TableBridge.toTable(rows) ?: error("table.csv 摊不成表")
        File(dir, "fromyaml.xlsx").writeBytes(XlsxWrite.workbook(listOf(SheetToWrite("table", table.records))).bytes)
        File(dir, "fromyaml.csv").writeText(Csv.render(table.records, Delimiter.Comma, LineEnding.Lf, false), Charsets.UTF_8)
        File(dir, "fromyaml.rows.txt").writeText(
            table.records.joinToString("\n") { it.joinToString("\u001F") } + "\n", Charsets.UTF_8,
        )
        assertEquals(table.records, Csv.parse(File(dir, "fromyaml.csv").readText(Charsets.UTF_8), Delimiter.Comma).records)
        assertEquals(tableDoc.records.size, table.records.size, "表该含表头那一行")
    }
}
