package com.fileforge.converter.engine

import com.fileforge.core.data.Yaml
import com.fileforge.core.data.YamlException
import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.TextDoc
import com.fileforge.core.json.Json
import com.fileforge.core.model.FileKind
import com.fileforge.core.text.TextCodecs
import com.fileforge.converter.data.WorkItem

/**
 * 任何来源（纯文本 / Markdown / 网页 / Word / 演示）摊成一棵文档树，只判断一次。
 *
 * 写成 Word、印成 PDF 与写成电子书三条出路都从这里拿同一棵树：
 * 各判各的话，同一份 Markdown 会出现"这份产物按记号排、那份按空行分段"两种结果，
 * 而产物说明里那句"按什么排的"也就对不上了。
 */
internal object SourceText {

    /** 树 + 要交代的话（按哪条路排、按什么编码读、抽正文丢了什么）+ 摊开来的原文。 */
    class Reading(val doc: Doc, val source: String, val notes: List<String>)

    fun of(item: WorkItem): Reading {
        val notes = ArrayList<String>()
        val source = when (item.kind) {
            FileKind.Docx, FileKind.Pptx -> {
                val extracted = OfficeSource.text(item.file, item.kind)
                notes += extracted.losses
                extracted.text
            }
            else -> {
                require(item.file.length() <= MAX_TEXT_BYTES) {
                    "这份文本 ${item.file.length() / 1024 / 1024} MB，超过 ${MAX_TEXT_BYTES / 1024 / 1024} MB 上限"
                }
                val decoded = TextCodecs.decodeForConversion(item.file.readBytes(), null)
                notes += "按 ${decoded.encoding.label} 读" + if (decoded.hadBom) "（源带 BOM）" else ""
                decoded.text
            }
        }
        val reading = TextDoc.read(source)
        return Reading(reading.doc, source, listOf(reading.route.note) + notes)
    }
}

/**
 * 读一份 YAML：判"像不像 YAML" → 解析成那棵共用的树，顺手记下"按什么编码读的""几份文档"。
 *
 * 转 JSON / 转 CSV / 转 XML / 转 Excel 四条路都从这里拿同一棵树 —— 各读各的话，
 * 同一份 YAML 会出现"这条路转得动、那条路说不是 YAML"的分歧。
 */
internal fun parseYamlTree(item: WorkItem, bytes: ByteArray): Pair<Json, List<String>> {
    val decoded = TextCodecs.decodeForConversion(bytes, null)
    require(Yaml.looksLikeYaml(decoded.text)) {
        "这份文件里没找到 YAML 的样子（既没有「键: 值」也没有「- 项」）。它本来就是普通文本。"
    }
    val tree = try {
        Yaml.parse(decoded.text)
    } catch (bad: YamlException) {
        throw IllegalArgumentException(bad.message)
    }
    val notes = ArrayList<String>()
    val docs = Yaml.documentCount(decoded.text)
    if (docs > 1) notes += "文件里有 $docs 份文档（用 --- 分隔），只转了第一份"
    notes += "按 ${decoded.encoding.label} 读" + if (decoded.hadBom) "（源带 BOM）" else ""
    return tree to notes
}
