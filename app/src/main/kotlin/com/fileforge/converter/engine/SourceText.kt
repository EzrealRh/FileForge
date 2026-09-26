package com.fileforge.converter.engine

import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.TextDoc
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
