package com.fileforge.converter.engine

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

    private fun read(item: WorkItem): ByteArray {
        require(item.file.length() <= MAX_TEXT_BYTES) {
            "这份文本 ${item.file.length() / 1024 / 1024} MB，超过 ${MAX_TEXT_BYTES / 1024 / 1024} MB 上限"
        }
        return item.file.readBytes()
    }
}
