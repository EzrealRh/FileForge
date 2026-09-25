package com.fileforge.core.text

/** 一条字幕：起止毫秒 + 若干行正文。 */
data class Cue(val startMs: Long, val endMs: Long, val lines: List<String>) {
    val text: String get() = lines.joinToString("\n")
}

enum class SubtitleFormat(
    val label: String,
    val extension: String,
    val resolutionMs: Int,
    /** 这一格式能不能放多行正文（LRC 一行只有一句，转过去必须并成一行）。 */
    val keepsLineBreaks: Boolean,
    /** LRC 只有开始时间 —— 转过去结束时间一定丢，界面上必须提醒。 */
    val keepsEndTime: Boolean,
) {
    Srt("SRT", "srt", 1, true, true),
    Vtt("WebVTT", "vtt", 1, true, true),
    /** `[mm:ss.xx]` 只能表达到 10 毫秒，一行一句，而且没有结束时间。 */
    Lrc("LRC 歌词", "lrc", 10, false, false),

    /** `H:MM:SS.cc` 的结尾是百分秒，同样只能到 10 毫秒。 */
    Ass("ASS / SSA", "ass", 10, true, true),
    ;

    /** 某条正文按这个格式装进去之后应该长成什么样 —— 转换前后比对就用它的期望值。 */
    fun expectedText(cue: Cue): String =
        if (keepsLineBreaks) cue.text else cue.lines.joinToString(" ").trim()

    /** 转到这个格式会丢掉什么。界面上照这个列，别让用户以为是无损转换。 */
    fun losses(cues: List<Cue>): List<String> {
        val out = ArrayList<String>()
        if (!keepsEndTime && cues.isNotEmpty()) out += "结束时间（$label 只有开始时间，再转回去时按默认停留时长补）"
        if (!keepsLineBreaks && cues.any { it.lines.size > 1 }) out += "多行正文会并成一行"
        if (cues.any { it.startMs % resolutionMs != 0L || it.endMs % resolutionMs != 0L }) {
            out += "时间轴精度降到 ${resolutionMs} 毫秒"
        }
        return out
    }
}

/**
 * 字幕与歌词互转。
 *
 * 四种格式的时间轴写法互不相同：SRT 是 `HH:MM:SS,mmm`，VTT 是 `HH:MM:SS.mmm`
 * 且允许省小时，LRC 是 `[mm:ss.xx]`（还可能一行多个时间戳、带 `[offset:]`），
 * ASS 是 `H:MM:SS.cc`（**百分秒**，不是毫秒），而且**它的列顺序由文件自己的
 * `Format:` 行决定** —— 把"第 9、10 列"当常量写死，换个压制脚本导出的文件就整列错位。
 *
 * 解析宁可报错也不静默跳过：一份 800 条的字幕坏 1 条时，跳过会交付一份缺字幕的产物，
 * 而没人会去数条数。
 */
object Subtitles {

    /** 解析失败。[line] 是 1 起的行号，界面上要原样带给用户。 */
    class Bad(val line: Int, message: String) : IllegalArgumentException("第 $line 行：$message")

    /** LRC 只给开始时间，转成有结束时间的格式时要补一个默认停留时长。 */
    const val LRC_DEFAULT_MS = 4_000L

    fun parse(format: SubtitleFormat, source: String): List<Cue> = when (format) {
        SubtitleFormat.Srt -> parseBlocks(source, "SRT")
        SubtitleFormat.Vtt -> {
            // 规范要求第一行是 WEBVTT。不查就会把一份普通文本当字幕读，读出一堆莫名其妙的报错
            val head = normalize(source).split('\n').firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (!head.startsWith("WEBVTT")) throw Bad(1, "WebVTT 第一行必须是 WEBVTT（可以带后缀），这份是「$head」")
            parseBlocks(source, "WebVTT").map { cue ->
                // VTT 正文里的 <c.xxx> / <v 名字> / <i> 是样式标记，不是要显示的字
                cue.copy(lines = cue.lines.map { it.replace(Regex("<[^>]*>"), "").trim() })
            }
        }
        SubtitleFormat.Lrc -> parseLrc(source)
        SubtitleFormat.Ass -> parseAss(source)
    }

    fun render(format: SubtitleFormat, cues: List<Cue>): String {
        val ordered = cues.sortedWith(compareBy({ it.startMs }, { it.endMs }))
        return when (format) {
            SubtitleFormat.Srt -> renderSrt(ordered)
            SubtitleFormat.Vtt -> renderVtt(ordered)
            SubtitleFormat.Lrc -> renderLrc(ordered)
            SubtitleFormat.Ass -> renderAss(ordered)
        }
    }

    /** 从 from 转到 to 会丢什么：目标格式的损失，加上精度变粗这一条。 */
    fun lossesBetween(from: SubtitleFormat, to: SubtitleFormat, cues: List<Cue>): List<String> {
        val out = ArrayList(to.losses(cues))
        if (to.resolutionMs > from.resolutionMs) {
            out += "时间轴精度从 ${from.resolutionMs} 毫秒降到 ${to.resolutionMs} 毫秒"
        }
        return out.distinct()
    }

    /** 只有 VTT 和 ASS 有强制的文本签名；SRT / LRC 没有，只能靠解析。 */
    private fun signature(source: String): SubtitleFormat? {
        val head = source.trimStart()
        val first = head.lineSequence().firstOrNull()?.trim().orEmpty()
        if (first.startsWith("WEBVTT")) return SubtitleFormat.Vtt
        if (head.hasAssSection()) return SubtitleFormat.Ass
        return null
    }

    private fun String.hasAssSection(): Boolean =
        lineSequence().any { it.trim() == "[Script Info]" || it.trim() == "[Events]" || it.trim() == "[V4+ Styles]" }

    /** 转换前的问题清单（不是异常）：时间倒挂、重叠、空正文，界面上要能列出来。 */
    fun problems(cues: List<Cue>): List<String> {
        val out = ArrayList<String>()
        val reversed = cues.count { it.endMs < it.startMs }
        if (reversed > 0) out += "$reversed 条结束时间比开始还早"
        val empty = cues.count { it.lines.all { line -> line.isBlank() } }
        if (empty > 0) out += "$empty 条没有正文"
        val ordered = cues.sortedBy { it.startMs }
        val overlapping = (1 until ordered.size).count { ordered[it].startMs < ordered[it - 1].endMs }
        if (overlapping > 0) out += "$overlapping 条与上一条时间重叠"
        return out
    }

    /**
     * 认源格式。优先级：文件里的**格式签名** > 扩展名 > 逐个试解析。
     *
     * 签名必须排在扩展名前面，因为 VTT 的时间轴写法 SRT 解析器也认（两者都认
     * `A --> B`，小数点逗号都收）—— 只看扩展名的话，一个后缀写错的 .srt
     * 会被当成 SRT 复制一遍还报成功，用户以为转好了。
     */
    fun detect(fileName: String, source: String): Pair<SubtitleFormat, SubtitleFormat?> {
        val suffix = fileName.substringAfterLast('.', "")
        val byName = SubtitleFormat.entries.firstOrNull { it.extension.equals(suffix, ignoreCase = true) }
        val bySignature = signature(source)?.takeIf { runCatching { parse(it, source) }.isSuccess }
        if (bySignature != null) return bySignature to byName
        if (byName != null && runCatching { parse(byName, source) }.isSuccess) return byName to null
        val tried = ArrayList<String>()
        for (candidate in SubtitleFormat.entries) {
            if (candidate == byName) continue
            if (runCatching { parse(candidate, source) }.isSuccess) return candidate to byName
            tried += candidate.label
        }
        // 扩展名说是一种、内容按哪种都解不通 —— 两边都报出来，别只说"格式不对"
        val detail = byName?.let { runCatching { parse(it, source) }.exceptionOrNull()?.message }
        val claimed = byName?.label ?: "未知"
        val reason = if (detail == null) "" else "（$detail）"
        throw Bad(1, "扩展名写的是 $claimed，但按 ${tried.joinToString("/")} 都解不通$reason")
    }

    // ---- 分块解析（SRT 与 VTT 共用）------------------------------------------

    private fun parseBlocks(source: String, formatName: String): List<Cue> {
        val lines = normalize(source).split('\n')
        val cues = ArrayList<Cue>()
        var index = 0
        while (index < lines.size) {
            if (lines[index].isBlank()) { index++; continue }
            var end = index
            while (end < lines.size && lines[end].isNotBlank()) end++
            val block = lines.subList(index, end)
            val timingAt = block.indexOfFirst { it.contains("-->") }
            if (timingAt >= 0) {
                val (start, stop) = parseTimingLine(block[timingAt], index + timingAt + 1)
                val body = block.drop(timingAt + 1).map { it.trimEnd() }
                if (body.none { it.isNotBlank() }) {
                    throw Bad(index + timingAt + 1, "$formatName 的时间轴下面没有正文")
                }
                cues += Cue(start, stop, body.filter { it.isNotBlank() })
            }
            index = end
        }
        if (cues.isEmpty()) throw Bad(1, "没解析出任何一条 $formatName 字幕")
        return cues
    }

    /** `00:00:20,000 --> 00:00:24,000`，行尾可能还带 VTT 的位置设置，只取两侧第一个 token。 */
    private fun parseTimingLine(line: String, at: Int): Pair<Long, Long> {
        val halves = line.split("-->")
        if (halves.size < 2) throw Bad(at, "时间轴要有 --> ，拿到「$line」")
        val start = parseTime(halves[0].trim().firstToken(), at)
        val stop = parseTime(halves[1].trim().firstToken(), at)
        return start to stop
    }

    private fun String.firstToken(): String = trim().split(Regex("\\s+")).firstOrNull().orEmpty()

    /** 认 `HH:MM:SS,mmm`、`HH:MM:SS.mmm`、`MM:SS.mmm`（VTT 允许省小时）。 */
    internal fun parseTime(text: String, at: Int): Long {
        val parts = text.split(':')
        if (parts.isEmpty() || parts.size > 3) throw Bad(at, "时间轴「$text」读不出来")
        var hours = 0L
        val tail: List<String>
        if (parts.size == 3) {
            hours = parts[0].toLongOrNull() ?: throw Bad(at, "时间轴「$text」的小时不是数字")
            tail = parts.subList(1, 3)
        } else {
            tail = parts
        }
        if (tail.size != 2) throw Bad(at, "时间轴「$text」缺分或秒")
        val minute = tail[0].toLongOrNull() ?: throw Bad(at, "时间轴「$text」的分不是数字")
        val split = tail[1].split('.', ',')
        val second = split[0].toLongOrNull() ?: throw Bad(at, "时间轴「$text」的秒不是数字")
        return ((hours * 60 + minute) * 60 + second) * 1000 + fractionMillis(split.getOrNull(1).orEmpty(), text, at)
    }

    /** 小数位可能是 1~3 位：`.5` 是 500ms、`.05` 是 50ms、`.005` 才是 5ms。补零再截断，别按字面读成毫秒。 */
    private fun fractionMillis(fraction: String, text: String, at: Int): Long = when {
        fraction.isEmpty() -> 0L
        fraction.length == 1 -> (fraction.toLongOrNull()?.times(100)) ?: throw Bad(at, "时间轴「$text」的小数不对")
        fraction.length == 2 -> (fraction.toLongOrNull()?.times(10)) ?: throw Bad(at, "时间轴「$text」的小数不对")
        else -> (fraction.take(3).toLongOrNull()) ?: throw Bad(at, "时间轴「$text」的小数不对")
    }

    // ---- LRC ---------------------------------------------------------------

    private val lrcTag = Regex("""\[(.*?)]""")
    private val lrcTime = Regex("""^(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?$""")

    private fun parseLrc(source: String): List<Cue> {
        var offsetMs = 0L
        val cues = ArrayList<Cue>()
        normalize(source).split('\n').forEachIndexed { position, raw ->
            val line = raw.trim()
            if (!line.startsWith("[")) return@forEachIndexed
            val tags = lrcTag.findAll(line).map { it.groupValues[1].trim() }.toList()
            if (tags.isEmpty()) return@forEachIndexed
            val times = tags.mapNotNull { tag ->
                val match = lrcTime.matchEntire(tag) ?: return@mapNotNull null
                val minute = match.groupValues[1].toLong()
                val second = match.groupValues[2].toLong()
                val start = minute * 60_000 + second * 1000
                start + parseFraction(match.groupValues[3], position + 1)
            }
            val rest = line.substringAfterLast(']').trim()
            if (times.isEmpty()) {
                // 不是时间戳就是元数据。只认 [offset:]（它对整份生效），[ti:] [ar:] 这些不动
                val offset = tags.firstOrNull { it.startsWith("offset", ignoreCase = true) }
                    ?.substringAfter(':', "")?.trim()?.removePrefix("+")?.toLongOrNull()
                if (offset != null) offsetMs = offset
                return@forEachIndexed
            }
            if (rest.isEmpty()) return@forEachIndexed     // 纯占位的空行，转成字幕只会多出一批空条目
            times.forEach { start ->
                val shifted = (start + offsetMs).coerceAtLeast(0L)
                cues += Cue(shifted, shifted + LRC_DEFAULT_MS, listOf(rest))
            }
        }
        if (cues.isEmpty()) throw Bad(1, "没找到任何 [分:秒.百分之秒] 形式的时间戳，这份不是 LRC")
        return cues.sortedWith(compareBy({ it.startMs }, { it.endMs }))
    }

    private fun parseFraction(fraction: String, at: Int): Long = when {
        fraction.isEmpty() -> 0L
        fraction.length == 1 -> fraction.toLong() * 100
        fraction.length == 2 -> fraction.toLong() * 10
        else -> fraction.take(3).toLong()
    }

    // ---- ASS / SSA ---------------------------------------------------------

    private fun parseAss(source: String): List<Cue> {
        val lines = normalize(source).split('\n')
        var columns: List<String> = emptyList()
        val cues = ArrayList<Cue>()
        var seen = false
        lines.forEachIndexed { position, raw ->
            val line = raw.trim()
            when {
                line.startsWith("Format:", true) ->
                    columns = line.substringAfter(':').split(',').map { it.trim() }

                line.startsWith("Dialogue:", true) -> {
                    seen = true
                    val at = position + 1
                    val startAt = columns.indexOf("Start").takeIf { it >= 0 }
                        ?: throw Bad(at, "这份 ASS 里没有列出 Start 的 Format: 行，无法确定列顺序")
                    val endAt = columns.indexOf("End").takeIf { it >= 0 } ?: startAt + 1
                    // Text 永远是最后一列（它自己含逗号），所以按 limit 切，最后一份整体留着
                    val textAt = if (columns.isNotEmpty()) columns.size - 1 else 9
                    val fields = line.substringAfter(':').split(',', limit = textAt + 1)
                    if (fields.size <= textAt) throw Bad(at, "字段数不够，这一行不像 Dialogue")
                    cues += Cue(
                        parseAssTime(fields[startAt].trim(), at),
                        parseAssTime(fields[endAt].trim(), at),
                        splitAssText(fields[textAt]),
                    )
                }
            }
        }
        if (!seen) throw Bad(1, "这份文件里没有 Dialogue: 行，不是字幕脚本")
        return cues.sortedBy { it.startMs }
    }

    /** `0:00:20.00` —— 小时可以只占一位，结尾是**百分秒**（乘 10 才是毫秒）。 */
    private fun parseAssTime(text: String, at: Int): Long {
        val parts = text.split(':')
        if (parts.size != 3) throw Bad(at, "时间轴要写成 时:分:秒.百分之秒，拿到「$text」")
        val hour = parts[0].toLongOrNull() ?: throw Bad(at, "时间轴「$text」读不出时")
        val minute = parts[1].toLongOrNull() ?: throw Bad(at, "时间轴「$text」读不出分")
        val split = parts[2].split('.')
        val second = split[0].toLongOrNull() ?: throw Bad(at, "时间轴「$text」读不出秒")
        return ((hour * 60 + minute) * 60 + second) * 1000 + parseFraction(split.getOrNull(1).orEmpty(), at)
    }

    /** `\N` `\n` 是换行，`\h` 是不间断空格，`{\...}` 是样式覆盖块 —— 都不该显示出来。 */
    private fun splitAssText(text: String): List<String> =
        text.replace(Regex("""\{[^}]*}"""), "")
            .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("") }

    // ---- 渲染 --------------------------------------------------------------

    private fun renderSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(clock(cue.startMs, "%02d:%02d:%02d,%03d")).append(" --> ")
                .append(clock(cue.endMs, "%02d:%02d:%02d,%03d")).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    private fun renderVtt(cues: List<Cue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEach { cue ->
            append(clock(cue.startMs, "%02d:%02d:%02d.%03d")).append(" --> ")
                .append(clock(cue.endMs, "%02d:%02d:%02d.%03d")).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    private fun renderLrc(cues: List<Cue>): String = buildString {
        cues.forEach { cue ->
            append(String.format(
                "[%02d:%02d.%02d]%s",
                cue.startMs / 60_000, (cue.startMs % 60_000) / 1000, (cue.startMs % 1000) / 10,
                // LRC 一行只放得下一句：多行正文并成一行，不能只留第一行把后面的字悄悄丢掉
                cue.lines.joinToString(" ").replace('\n', ' ').trim(),
            )).append('\n')
        }
    }

    private fun renderAss(cues: List<Cue>): String = buildString {
        append("[Script Info]\nScriptType: v4.00+\nPlayResX: 1280\nPlayResY: 720\n\n")
        append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, Alignment\n")
        append("Style: Default,Microsoft YaHei,42,&H00FFFFFF,2\n\n")
        append("[Events]\n")
        append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        cues.forEach { cue ->
            append("Dialogue: 0,").append(assClock(cue.startMs)).append(',')
                .append(assClock(cue.endMs)).append(",Default,,0,0,0,,")
                .append(cue.lines.joinToString("\\N")).append('\n')
        }
    }

    private fun assClock(ms: Long): String {
        val clamped = ms.coerceAtLeast(0)
        return String.format(
            "%d:%02d:%02d.%02d",
            clamped / 3_600_000, (clamped / 60_000) % 60, (clamped / 1000) % 60, (clamped % 1000) / 10,
        )
    }

    private fun clock(ms: Long, pattern: String): String {
        val clamped = ms.coerceAtLeast(0)
        return String.format(
            pattern, clamped / 3_600_000, (clamped / 60_000) % 60, (clamped / 1000) % 60, clamped % 1000,
        )
    }

    /** 统一换行，并去掉开头可能残留的 BOM 字符（它会混进第一行文本里）。 */
    private fun normalize(source: String): String =
        source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
}
