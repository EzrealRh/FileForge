package com.fileforge.core

import com.fileforge.core.data.Csv
import com.fileforge.core.data.Delimiter
import com.fileforge.core.data.TableBridge
import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonRender
import com.fileforge.core.text.LineEnding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JSON 输出侧与 CSV 互转。
 *
 * 参照物不都是自己：`data` 目录下的 `.truth` 由 Python 标准库 json / csv 生成
 * （见 tools/make_data_fixtures.py）。但**只在该信它的地方信**：Python 的 `dumps` 会把
 * `1.50` 改写成 `1.5`、把 `1e20` 写成 `1e+20`，而"不改写数字"恰恰是这里的诉求 ——
 * 所以数字用文本断言、转义与 CSV 单元格才拿 Python 当逐字节参照。
 */
class CsvJsonTest {

    // ---- JSON 输出侧 ----------------------------------------------------------

    @Test
    fun `格式化不许改写数字`() {
        val rendered = JsonRender.render(Json.parse(resourceText("nested.json")), indent = 2)
        // Python 会把这几个改写成别的写法，所以这里逐个钉住原文
        listOf("\"数量\": 1", "\"比值\": 1.50", "\"大数\": 9007199254740993", "\"科学\": 1e20", "\"负零\": -0")
            .forEach { assertTrue(rendered.contains(it), "少了「$it」：\n$rendered") }
        // 2^53+1 过一遍 double 会真的丢位，这条是那个前提的哨兵
        assertTrue(rendered.contains("9007199254740993"), "大整数被 double 吃掉了")
    }

    @Test
    fun `渲染是幂等的，再解一次还是同一棵树`() {
        val source = resourceText("nested.json")
        // 两次渲染要用同一个缩进比：拿缩进版跟紧凑版比当然不一样，那不算判据
        listOf(0, 2, 4).forEach { indent ->
            val once = JsonRender.render(Json.parse(source), indent = indent)
            assertEquals(once, JsonRender.render(Json.parse(once), indent = indent), "缩进 $indent 渲染两遍结果不一样")
        }
    }

    @Test
    fun `字符串转义与 Python 的 dumps 逐字节一致`() {
        // 这份可以逐字节比：转义形态两边是按同一份规范写的，Python 也不改写这里的数字
        assertEquals(
            resourceText("escapes.json.truth").trim(),
            JsonRender.render(Json.parse(resourceText("escapes.json"))),
            "引号、反斜杠、控制字符、DEL、代理对里任一处对不上都会毁掉内容",
        )
    }

    @Test
    fun `非 ASCII 转码是可选项而不是默认`() {
        val json = Json.parse("""{"键":"中文aé"}""")
        assertEquals("""{"键":"中文aé"}""", JsonRender.render(json))
        val ascii = JsonRender.render(json, escapeNonAscii = true)
        assertTrue(ascii.contains("\\u4e2d"), "开了开关就要真转：$ascii")
        assertEquals("""{"键":"中文aé"}""", JsonRender.render(Json.parse(ascii)), "转完再解回来得是同一份")
    }

    @Test
    fun `键序默认保留而排序是显式的`() {
        val json = Json.parse("""{"b":1,"a":2,"c":3}""")
        assertEquals("""{"b":1,"a":2,"c":3}""", JsonRender.render(json))
        assertEquals("""{"a":2,"b":1,"c":3}""", JsonRender.render(json, sortKeys = true))
    }

    @Test
    fun `空容器在缩进形态下也不许散架`() {
        val source = """{"a":[],"b":{},"c":[{}],"d":[{"e":{}}]}"""
        assertEquals(source, JsonRender.render(Json.parse(source)))
        val pretty = JsonRender.render(Json.parse(source), indent = 2)
        assertTrue("\n\n" !in pretty, "缩进里不该有凭空多出的空行：\n$pretty")
        assertEquals(source, JsonRender.render(Json.parse(pretty)), "缩进版解回来还要是同一份")
    }

    @Test
    fun `数字语法按 JSON 规范收而不是按 double 能认什么收`() {
        // Java 的 toDouble 认这些，放过去再照抄渲染就会产出非法 JSON
        listOf("+5", "1.", ".5", "3d", "Infinity", "01", "1e", "-", "0x10", "1_000").forEach { bad ->
            assertThrows(Exception::class.java, { Json.parse(bad) }, "$bad 不该被当成合法 JSON")
        }
        listOf("0", "-0", "0.5", "1e5", "1E+5", "-2.5e-3", "123456789012345678901234567890").forEach { good ->
            assertEquals(good, JsonRender.render(Json.parse(good)), "$good 该原样通过")
        }
    }

    // ---- CSV 读：单元格逐个对 Python 的 csv --------------------------------------

    @Test
    fun `Python 的 csv 读出来的单元格我们一格一格读对`() {
        val truth = Json.parse(resourceText("tricky.csv.truth"))
        val doc = Csv.parse(resourceText("tricky.csv"))
        assertEquals(Delimiter.Comma, doc.delimiter)
        assertEquals(truth.arrayValue.size, doc.records.size, "行数对不上")
        truth.arrayValue.forEachIndexed { rowIndex, row ->
            assertEquals(
                row.arrayValue.map { it.stringValue },
                doc.records[rowIndex],
                "第 ${rowIndex + 1} 行与 Python 读的不一致",
            )
        }
        assertTrue("\n" in doc.records[2][1], "引号里的换行属于内容")
        assertEquals(LineEnding.CrLf, doc.ending, "原文件是 CRLF，产物也要跟着 CRLF")
    }

    @Test
    fun `中间出现的引号按字面处理而不是当转义`() {
        // Excel 与 Python 的 csv 都这么做；当转义吃掉的话导一圈内容就变了
        assertEquals(listOf("a", "b\"c\"d"), Csv.parse("a,b\"c\"d\n").records.first())
    }

    @Test
    fun `分隔符按引号外的票数猜`() {
        assertEquals(Delimiter.Semicolon, Csv.detect("a;b;c\n1;2;3\n"))
        assertEquals(Delimiter.Tab, Csv.detect("a\tb\n1\t2\n"))
        assertEquals(Delimiter.Comma, Csv.detect("a,b\n1,2\n"))
        assertEquals(Delimiter.Comma, Csv.detect("就一行没有分隔符\n"), "谁都数不到时按逗号，别报个怪东西")
        // 字段里写了逗号也不该改判：数的是整段里引号以外的票
        assertEquals(Delimiter.Semicolon, Csv.detect("备注;金额\n含,逗号;12\n第二,行;34\n第三,行;56\n"))
        assertEquals(
            Delimiter.Comma,
            Csv.parse("a,\"含;分号\"\n").delimiter,
            "引号里的分号不许参与投票",
        )
    }

    @Test
    fun `CRLF 与 BOM 与末尾空行都不许留下噪音`() {
        val doc = Csv.parse("${Csv.BOM}a,b\r\n1,2\r\n")
        assertEquals(listOf("a", "b"), doc.records[0], "BOM 会粘在第一个列名上")
        assertEquals(2, doc.records.size, "结尾那个换行不该多出一条空记录：${doc.records}")
        assertEquals(LineEnding.CrLf, doc.ending)
    }

    @Test
    fun `行列不齐要报行号`() {
        assertEquals(listOf(2), Csv.parse("a,b,c\n1,2\n3,4,5\n").ragged)
        assertEquals(listOf(1, 3), Csv.parse("a\n1,2\n3\n").ragged, "以最宽那行为准，第 1、3 行都少一列")
        assertEquals(emptyList<Int>(), Csv.parse("a,b\n1,2\n").ragged)
    }

    // ---- CSV 写与整圈往返 ------------------------------------------------------

    @Test
    fun `渲染只在必要处加引号`() {
        assertEquals(
            "普通,\"含,逗号\",\"含\"\"引号\",\"含\n换行\"\n",
            Csv.render(listOf(listOf("普通", "含,逗号", "含\"引号", "含\n换行"))),
        )
        assertEquals("\"a\",\"b\"\n", Csv.render(listOf(listOf("a", "b")), quoteAll = true))
        // 读写要闭合：渲染出去再读回来必须一模一样，含最阴险的那几种格子
        val cells = listOf("普通", "含,逗号", "含\"引号", "含\n换行", "含\r\n两种", "", "  前后空格  ")
        Delimiter.entries.forEach { delimiter ->
            val text = Csv.render(listOf(cells), delimiter)
            assertEquals(cells, Csv.parse(text, delimiter).records.first(), "${delimiter.label} 这一圈没闭合")
        }
    }

    @Test
    fun `表转回 JSON 时不许猜类型除非明说`() {
        val doc = Csv.parse(resourceText("table.csv"))
        // Python 的 DictReader 出来全是字符串，所以这份参照可以逐字节比
        assertEquals(
            resourceText("table.csv.truth").trim(),
            JsonRender.render(TableBridge.toRowsJson(doc, header = true, inferTypes = false)),
            "默认不许改格子内容：1.50 与 007 都得原样是字符串",
        )
        val typed = TableBridge.toRowsJson(doc, header = true, inferTypes = true).arrayValue.first()
        assertEquals("007", typed.field("编号")?.stringValue, "前导零是编号的一部分，猜成 7 就再也回不去")
        assertEquals(42.0, typed.field("数量")?.numberValue ?: 0.0)
        // 1.50 按 JSON 语法是数字，所以开着识别时它变成数字：原文照抄（尾零还在文本里），
        // 但任何 JSON 库读回去都是 1.5 —— 写法这层信息在 JSON 里没有容身之处，界面要如实说
        assertEquals("1.50", typed.field("比值")?.numberText, "渲染不许改写数字写法")
        assertEquals(null, typed.field("比值")?.stringValue, "开着识别时它是数字而不是字符串")
        assertEquals(true, typed.field("标记")?.boolValue)
        assertEquals("N/A", typed.field("文本")?.stringValue)
    }

    @Test
    fun `JSON 转表会把嵌套压成一格文本并事先声明`() {
        val json = Json.parse("""[{"名":"甲","标签":["a","b"]},{"名":"乙","标签":[]}]""")
        val table = TableBridge.toTable(json)
        assertNotNull(table)
        assertEquals(listOf("名", "标签"), table!!.columns)
        assertEquals("""["a","b"]""", table.rows[0][1], "嵌套值压成一行 JSON 文本")
        assertEquals("[]", table.rows[1][1])
        val losses = TableBridge.losses(json)
        assertTrue(losses.any { it.contains("嵌套") }, "不声明嵌套丢法就不许转：$losses")
        assertTrue(losses.any { it.contains("只有文字") }, "类型丢失是最要紧的一条：$losses")
    }

    @Test
    fun `对象数组以外的形状也各有去处`() {
        val arrays = TableBridge.toTable(Json.parse("""[[1,2],[3]]"""))!!
        assertEquals(listOf("列1", "列2"), arrays.columns)
        assertEquals(listOf("3", ""), arrays.rows[1], "短的补空而不是整体报错")

        val scalars = TableBridge.toTable(Json.parse("""["甲","乙"]"""))!!
        assertEquals(listOf("值"), scalars.columns)
        assertEquals(listOf(listOf("甲"), listOf("乙")), scalars.rows)

        assertNotNull(TableBridge.reasonWhyNotTable(Json.parse("""{"a":1}""")), "最外层是对象要说清为什么不行")
        assertNotNull(TableBridge.reasonWhyNotTable(Json.parse("[]")), "空数组定不出列")
        assertNull(TableBridge.reasonWhyNotTable(Json.parse("""[{"a":1}]""")))
    }

    @Test
    fun `字段数不一样的条目并集成一排列缺的留空`() {
        val table = TableBridge.toTable(Json.parse("""[{"a":1,"b":2},{"a":3}]"""))!!
        assertEquals(listOf("a", "b"), table.columns)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "")), table.rows)
        assertTrue(TableBridge.losses(Json.parse("""[{"a":1,"b":2},{"a":3}]""")).any { it.contains("并集") })
    }

    @Test
    fun `表头重复或为空要补出可用的键`() {
        assertEquals(listOf("a", "b", "列3", "a_2", "a_3"), TableBridge.dedupe(listOf("a", "b", "", "a", "a")))
        assertEquals(
            """[{"a":"1","a_2":"2","b":"3"}]""",
            JsonRender.render(TableBridge.toRowsJson(Csv.parse("a,a,b\n1,2,3\n", Delimiter.Comma), true, false)),
            "重名表头不补编号的话，JSON 里会直接少一个字段",
        )
    }

    @Test
    fun `产物落盘交给 Python 复核`() {
        // 判据是"json.loads / csv.reader 认不认、语义相不相同"，那只能交给 Python 去看
        val dir = java.io.File("build/data").apply { mkdirs() }
        val source = resourceText("nested.json")
        val doc = Csv.parse(resourceText("tricky.csv"))
        java.io.File(dir, "reparsed.csv").writeText(Csv.render(doc.records, doc.delimiter, doc.ending))
        java.io.File(dir, "nested.compact.json").writeText(JsonRender.render(Json.parse(source)))
        java.io.File(dir, "nested.indent2.json").writeText(JsonRender.render(Json.parse(source), indent = 2))
        java.io.File(dir, "escapes.round.json").writeText(
            JsonRender.render(Json.parse(resourceText("escapes.json"))),
        )
        val table = Csv.parse(resourceText("table.csv"))
        java.io.File(dir, "table.as-strings.json").writeText(
            JsonRender.render(TableBridge.toRowsJson(table, header = true, inferTypes = false)),
        )
        java.io.File(dir, "table.typed.json").writeText(
            JsonRender.render(TableBridge.toRowsJson(table, header = true, inferTypes = true)),
        )
        java.io.File(dir, "csv-as-arrays.json").writeText(
            JsonRender.render(TableBridge.toRowsJson(table, header = false, inferTypes = false)),
        )
        listOf("reparsed.csv", "nested.compact.json", "table.as-strings.json").forEach { name ->
            assertTrue(java.io.File(dir, name).length() > 0, "$name 是空的")
        }
    }

    private fun resourceText(name: String): String =
        javaClass.classLoader.getResourceAsStream("data/$name").use { input ->
            requireNotNull(input) { "缺少夹具 data/$name，先跑 python tools/make_data_fixtures.py" }
            String(input.readBytes(), Charsets.UTF_8)
        }
}
