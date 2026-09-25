package com.fileforge.converter.engine

import com.fileforge.core.data.Csv
import com.fileforge.core.data.TableBridge
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
class TextEngine(private val workspace: Workspace) {

    private companion object {
        /** 手机上没人拿几百 MB 的文本转编码；这条是防爆内存的上限，不是功能限制。 */
        const val MAX_TEXT_BYTES = 64L * 1024 * 1024
    }

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
