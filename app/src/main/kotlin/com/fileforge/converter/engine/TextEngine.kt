package com.fileforge.converter.engine

import com.fileforge.core.data.Csv
import com.fileforge.core.data.TableBridge
import com.fileforge.core.data.Xml
import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonException
import com.fileforge.core.json.JsonRender
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.Operation
import com.fileforge.core.text.LineEndings
import com.fileforge.core.text.Subtitles
import com.fileforge.core.text.TextCodecs
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace

/**
 * 文本类转换：换编码 / 统一换行，以及字幕与歌词互转。
 *
 * 判断全在 `:core`（TextCodecs、Subtitles），这里只负责读写 —— 这两条路上没有任何安卓 API，
 * 所以判据都能在纯 JVM 上跑单测，真机只负责点按钮。
 */
/** 手机上没人拿几百 MB 的文本转编码或印成 PDF；这条是防爆内存的上限，不是功能限制。 */
internal const val MAX_TEXT_BYTES = 64L * 1024 * 1024

/** 表题要进文件名，太长会把名字挤没。 */
private const val MAX_TABLE_TAG = 24

class TextEngine(private val workspace: Workspace) {

    /** 换编码，顺带统一换行风格。目标装不下的字要数出来，不能悄悄换成问号。 */
    fun convertText(item: WorkItem, operation: Operation.ConvertTextEncoding): EngineOutput {
        val bytes = read(item)
        val source = TextCodecs.decodeForConversion(bytes, operation.source)
        val normalized = LineEndings.convert(source.text, operation.ending)
        val encoded = TextCodecs.encode(normalized, operation.target, operation.bom)
        require(encoded.clean) {
            "目标编码 ${operation.target.label} 装不下这 ${encoded.dropped} 个字符 —— 换 UTF-8 就什么都装得下"
        }
        val extension = item.extension.ifBlank { "txt" }
        val output = workspace.newStagingFile(extension).apply { writeBytes(encoded.bytes) }
        val notes = ArrayList<String>()
        notes += "按 ${source.encoding.label} 读" + if (operation.source == null) "（自动认出）" else "（你指定）"
        if (source.hadBom) notes += "源带 BOM" + if (operation.bom) "并保留" else "已去掉"
        notes += "换行 ${LineEndings.detect(source.text)?.label ?: "无"} → ${operation.ending.label}"
        notes += "${normalized.count { it == '\n' } + 1} 行 · ${encoded.bytes.size} 字节"
        return EngineOutput(
            OutputNaming.tagged(item.name, operation.target.shortTag, extension),
            output,
            notes.joinToString(" · "),
        )
    }

    /**
     * 字幕 / 歌词转格式。输出一律写 UTF-8 —— 播放器对非 UTF-8 的字幕普遍直接显示乱码，
     * 而这份文件是不是 UTF-8 用户在手机上看不出来，所以在这里固定下来。
     */
    fun convertSubtitle(item: WorkItem, operation: Operation.ConvertSubtitle): EngineOutput {
        val bytes = read(item)
        val decoded = TextCodecs.decodeForConversion(bytes, operation.source)
        val (from, claimed) = Subtitles.detect(item.name, decoded.text)
        val cues = Subtitles.parse(from, decoded.text)
        val output = workspace.newStagingFile(operation.target.extension).apply {
            writeText(Subtitles.render(operation.target, cues))
        }
        val notes = ArrayList<String>()
        notes += if (claimed == null || claimed == from) {
            "源 ${from.label}"
        } else {
            "扩展名写的是 ${claimed.label}，按内容判为 ${from.label}"
        }
        notes += "${cues.size} 条"
        if (!decoded.clean) notes += "源编码有 ${decoded.replaced} 处读不出"
        Subtitles.problems(cues).forEach { notes += "原件$it" }
        Subtitles.lossesBetween(from, operation.target, cues).forEach { notes += "转过去会丢$it" }
        return EngineOutput(
            OutputNaming.tagged(item.name, "", operation.target.extension),
            output,
            notes.joinToString(" · "),
        )
    }

    /**
     * JSON 格式化：缩进/压平、键排序、非 ASCII 转码。
     *
     * 数字一律照抄原文（`:core` 那侧保证），所以 `1.50` 与大整数不会被改写。
     */
    fun formatJson(item: WorkItem, operation: Operation.FormatJson): EngineOutput {
        val json = parseJson(item)
        val text = JsonRender.render(
            json,
            indent = if (operation.pretty) operation.indent else 0,
            sortKeys = operation.sortKeys,
            escapeNonAscii = operation.ascii,
        )
        val entries = json.arrayValue.size.takeIf { it > 0 } ?: json.members.size
        return EngineOutput(
            OutputNaming.tagged(item.name, if (operation.pretty) "缩进" else "压平", "json"),
            writeText(item, text, "json"),
            "${if (operation.pretty) "缩进 ${operation.indent} 空格" else "压成一行"}" +
                (if (operation.sortKeys) " · 键已排序" else "") +
                (if (operation.ascii) " · 非 ASCII 已转码" else "") +
                " · 顶层 $entries 项",
        )
    }

    /** JSON 数组 → CSV。会丢的东西在动手之前就列出来，不假装是无损转换。 */
    fun jsonToCsv(item: WorkItem, operation: Operation.JsonToCsv): EngineOutput {
        val json = parseJson(item)
        TableBridge.reasonWhyNotTable(json)?.let { throw IllegalArgumentException("转不成表：$it") }
        val table = TableBridge.toTable(json) ?: throw IllegalArgumentException("转不成表：这份 JSON 的形状没认出来")
        val text = Csv.render(table.records, operation.delimiter, operation.ending, operation.quoteAll)
        val notes = ArrayList<String>()
        notes += "${table.rows.size} 行 × ${table.columns.size} 列"
        TableBridge.losses(json).forEach { notes += "转过去会丢$it" }
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "csv"),
            writeText(item, text, "csv"),
            notes.joinToString(" · "),
        )
    }

    /**
     * CSV → JSON。默认**不猜类型**：`007` 认成 7、`1.50` 认成 1.5 都是不可逆的，
     * 所以只有用户明确要才开，开的时候也说明会丢哪一层写法。
     */
    fun csvToJson(item: WorkItem, operation: Operation.CsvToJson): EngineOutput {
        val bytes = read(item)
        val decoded = TextCodecs.decodeForConversion(bytes, null)
        val doc = Csv.parse(decoded.text, Csv.detect(decoded.text))
        require(!doc.isEmpty) { "这份 CSV 里一行内容都没有" }
        val text = JsonRender.render(
            TableBridge.toRowsJson(doc, operation.header, operation.inferTypes),
            indent = operation.indent,
        )
        val notes = ArrayList<String>()
        notes += if (operation.header) "${doc.records.first().size} 列 · ${doc.records.size - 1} 行数据"
        else "${doc.records.size} 行 × ${doc.widest} 列"
        notes += if (operation.inferTypes) "已按数字与真假识别类型" else "格子一律当字符串"
        if (operation.inferTypes) notes += "开类型识别会丢${TableBridge.inferLosses}"
        if (doc.ragged.isNotEmpty()) notes += "第 ${doc.ragged.joinToString("、")} 行的列数跟别处不一样"
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "json"),
            writeText(item, text, "json"),
            notes.joinToString(" · "),
        )
    }

    /** XML → JSON。约定（子元素成数组、`@` 属性、`#text`）在 `:core` 的 Xml 头部写着。 */
    fun xmlToJson(item: WorkItem, operation: Operation.XmlToJson): EngineOutput {
        val text = readText(item)
        val json = Xml.parse(text)
        val root = json.members.keys.firstOrNull() ?: "根"
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "json"),
            writeText(item, JsonRender.render(json, indent = operation.indent), "json"),
            "根元素 $root · ${countElements(json)} 个节点 · 注释与处理指令按约定丢掉",
        )
    }

    /** JSON → XML。根元素名默认跟源文件名，键名必须能当标签名，不能的就报错而不是改名。 */
    fun jsonToXml(item: WorkItem, operation: Operation.JsonToXml): EngineOutput {
        val json = parseJson(item)
        val root = operation.root.trim().ifBlank { OutputNaming.stem(item.name) }
        val xml = try {
            Xml.render(json, root = root, indent = operation.indent)
        } catch (bad: IllegalArgumentException) {
            throw IllegalArgumentException(bad.message ?: "这份 JSON 的键名不能当 XML 标签用")
        }
        val name = OutputNaming.sanitize(root).ifBlank { "root" }
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "xml"),
            writeText(item, xml, "xml"),
            "根元素 $name · ${countElements(json)} 个节点",
        )
    }

    /**
     * Markdown → HTML 页面。
     *
     * 包一层完整文档（DOCTYPE + `<meta charset>`）不是啰嗦：一份只写着正文片段的 .html 交给浏览器，
     * 它会按系统默认编码去猜，中文十次有九次猜错 —— 那副样子是"文件坏了"而不是"编码没声明"。
     */
    fun markdownToHtml(item: WorkItem): EngineOutput {
        val source = requireMarkdown(readText(item))
        val rendered = com.fileforge.core.doc.Markdown.toHtml(source)
        val page = buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\" />\n<title>")
            append(com.fileforge.core.doc.Markdown.htmlEscape(OutputNaming.stem(item.name)))
            append("</title>\n</head>\n<body>\n")
            append(rendered.text)
            append("</body>\n</html>\n")
        }
        val notes = ArrayList<String>()
        notes += "${rendered.text.lines().size} 行"
        notes += rendered.notes
        notes += "包了 DOCTYPE 与 charset，浏览器直接打开不会把中文猜成乱码"
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "html"),
            writeText(item, page, "html"),
            notes.joinToString(" · "),
        )
    }

    /** Markdown → 纯文本：标记吃掉，列表记号与表格分列留着。 */
    fun markdownToText(item: WorkItem): EngineOutput {
        val source = requireMarkdown(readText(item))
        val rendered = com.fileforge.core.doc.Markdown.toPlainText(source)
        val notes = ArrayList<String>()
        notes += "${rendered.text.count { it == '\n' }} 行"
        notes += rendered.notes
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "txt"),
            writeText(item, rendered.text, "txt"),
            notes.joinToString(" · "),
        )
    }

    private fun requireMarkdown(text: String): String {
        require(com.fileforge.core.doc.Markdown.looksLikeMarkdown(text)) {
            "这份文本里没找到任何 Markdown 记号（# 标题、- 列表、``` 代码、> 引用、| 表格），" +
                "硬转只会产出一份看着一样、少了星号的文件。要换编码请用「文本转编码」"
        }
        return text
    }

    /**
     * 网页 → 纯文本：段落、列表记号、表格分列都留着，脚本样式与页眉丢掉。
     *
     * 标签没闭合会被按浏览器的补法补上，补了几处写在结果说明里 —— 那说明源文件本身写坏了，
     * 抽出来的结构可能不是作者想的那样，不能装作没发生。
     */
    fun htmlToText(item: WorkItem): EngineOutput {
        val rendered = com.fileforge.core.doc.Html.toPlainText(requireHtml(readText(item)))
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "txt"),
            writeText(item, rendered.text, "txt"),
            (listOf("${rendered.text.count { it == '\n' }} 行") + rendered.notes).joinToString(" · "),
        )
    }

    /** 网页 → Markdown：标题、列表、表格、链接写成标记；表单与内嵌框架只剩文字，也会说出来。 */
    fun htmlToMarkdown(item: WorkItem): EngineOutput {
        val rendered = com.fileforge.core.doc.Html.toMarkdown(requireHtml(readText(item)))
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "md"),
            writeText(item, rendered.text, "md"),
            (listOf("${rendered.text.lines().size} 行") + rendered.notes).joinToString(" · "),
        )
    }

    /**
     * 网页里的表逐张转 CSV：有表题的拿表题当文件名后缀，没有的按序号。
     *
     * 跨度（colspan / rowspan）按占位处理 —— 被盖住的位置留空格子。把跨格那一格只写一次、
     * 后面的格子往前挤，行列数看着齐了，其实每一列都错位一格，那种错在成品里根本看不出来。
     */
    fun htmlToCsv(
        item: WorkItem,
        delimiter: com.fileforge.core.data.Delimiter,
        ending: com.fileforge.core.text.LineEnding,
    ): List<EngineOutput> {
        val tables = com.fileforge.core.doc.Html.toTables(requireHtml(readText(item)))
        val filled = tables.filter { it.rows.isNotEmpty() }
        require(filled.isNotEmpty()) {
            "这份网页里没有一张有格子的表" +
                (tables.firstOrNull()?.notes?.let { "（${it.joinToString("；")}）" } ?: "（没找到 <table>）")
        }
        return filled.mapIndexed { position, table ->
            val notes = ArrayList<String>()
            notes += "${table.rows.size} 行 × ${table.rows.maxOf { it.size }} 列"
            notes += table.notes
            if (filled.size != tables.size) notes += "另有 ${tables.size - filled.size} 张空表没出文件"
            EngineOutput(
                OutputNaming.tagged(
                    item.name,
                    if (filled.size == 1) "" else tableTag(table, position),
                    "csv",
                ),
                writeText(item, Csv.render(table.rows, delimiter, ending), "csv"),
                notes.joinToString(" · "),
            )
        }
    }

    /** 表题是作者写的，拿来当文件名后缀；没有表题的按序号。只有一张表时不加后缀。 */
    private fun tableTag(table: com.fileforge.core.doc.HtmlTable, position: Int): String =
        if (table.named) OutputNaming.sanitize(table.name).take(MAX_TABLE_TAG) else "表${position + 1}"

    private fun requireHtml(text: String): String {
        require(com.fileforge.core.doc.Html.looksLikeHtml(text)) {
            "这份文件里没找到 HTML 标签（<!doctype>、<html>、<p>、<div> 这些），它本来就是纯文本。" +
                "要去掉 Markdown 标记请用「Markdown 去标记」"
        }
        return text
    }

    /** 数一棵树里有多少个值节点，给结果说明用（"转成功了"得有个可看的量）。 */
    private fun countElements(json: Json): Int = when {
        json.members.isNotEmpty() -> json.members.values.sumOf { countElements(it) } + 1
        json.arrayValue.isNotEmpty() -> json.arrayValue.sumOf { countElements(it) } + 1
        else -> 1
    }

    private fun readText(item: WorkItem): String {
        val decoded = TextCodecs.decodeForConversion(read(item), null)
        return decoded.text
    }

    private fun parseJson(item: WorkItem): Json {
        val bytes = read(item)
        val decoded = TextCodecs.decodeForConversion(bytes, null)
        val text = decoded.text.trim()
        require(text.isNotEmpty()) { "这份文件是空的" }
        // decodeForConversion 已经保证"读不干净就不给结果"，所以这里只管语法对不对
        return try {
            Json.parse(text)
        } catch (bad: JsonException) {
            throw IllegalArgumentException("这不是合法 JSON：${bad.message}")
        }
    }

    /** 产物固定 UTF-8 无 BOM：JSON 与 CSV 的规范都以 UTF-8 为默认，BOM 会让一些解析器直接报错。 */
    private fun writeText(item: WorkItem, text: String, extension: String): java.io.File =
        workspace.newStagingFile(extension).apply { writeText(text) }

    private fun read(item: WorkItem): ByteArray {
        require(item.file.length() <= MAX_TEXT_BYTES) {
            "这份文本 ${item.file.length() / 1024 / 1024} MB，超过 ${MAX_TEXT_BYTES / 1024 / 1024} MB 上限"
        }
        return item.file.readBytes()
    }
}
