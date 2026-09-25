package com.fileforge.core

import com.fileforge.core.text.Cue
import com.fileforge.core.text.SubtitleFormat
import com.fileforge.core.text.Subtitles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 字幕互转。四种格式各挑最容易踩的点钉住，因为转错的表现是"字幕错位或整段消失"，
 * 而播放器不会告诉你原因。
 */
class SubtitlesTest {

    private val srt = """
        1
        00:00:20,000 --> 00:00:24,400
        第一句
        还在同一句里

        2
        00:01:02,345 --> 00:01:05,678
        第二句
    """.trimIndent() + "\n"

    @Test
    fun `SRT 逗号毫秒与多行正文`() {
        val cues = Subtitles.parse(SubtitleFormat.Srt, srt)
        assertEquals(2, cues.size)
        assertEquals(20_000L, cues[0].startMs)
        assertEquals(24_400L, cues[0].endMs)
        assertEquals(listOf("第一句", "还在同一句里"), cues[0].lines)
        assertEquals(62_345L, cues[1].startMs, "00:01:02,345 就是 62.345 秒")
    }

    @Test
    fun `VTT 允许省小时并剥掉样式标记`() {
        val vtt = """
            WEBVTT - 来自某处

            00:20.000 --> 00:24.400 line:80%
            <v 旁白>这是<b>带标记</b>的一句

            note-2
            00:01:02.345 --> 00:01:05.678
            另一句
        """.trimIndent()
        val cues = Subtitles.parse(SubtitleFormat.Vtt, vtt)
        assertEquals(2, cues.size)
        assertEquals(20_000L, cues[0].startMs, "省掉小时的写法也要认")
        assertEquals("这是带标记的一句", cues[0].lines.single(), "样式标记不是要显示的字")
        assertEquals("另一句", cues[1].lines.single(), "块里的 cue 标识行不该进正文")
    }

    @Test
    fun `VTT 缺头就报错而不是当普通文本读`() {
        val bad = assertThrows(Subtitles.Bad::class.java) {
            Subtitles.parse(SubtitleFormat.Vtt, "1\n00:00:01.000 --> 00:00:02.000\n没有头")
        }
        assertTrue(bad.message!!.contains("WEBVTT"), bad.message)
    }

    @Test
    fun `LRC 一行多时间戳与 offset`() {
        val lrc = """
            [ti:一首歌]
            [ar:某人]
            [offset:+150]
            [00:12.00][00:52.00]副歌同一句
            [00:20.5]半秒位的
            [00:30.000]三位小数
            [00:40.00]
        """.trimIndent()
        val cues = Subtitles.parse(SubtitleFormat.Lrc, lrc)
        // [offset:+150] 的语义是加到每个时间戳上；一行两个时间戳就展开成两条
        assertEquals(listOf(12_150L, 20_650L, 30_150L, 52_150L), cues.map { it.startMs }, "排序与 offset 都要对")
        // offset 对每一条时间戳生效，20.5 秒也一起后移 150 毫秒
        assertEquals(4, cues.size, "正文为空的那行不该变成一条字幕")
        assertTrue(cues[1].endMs - cues[1].startMs == Subtitles.LRC_DEFAULT_MS, "LRC 没有结束时间，要补一个")
        assertEquals("副歌同一句", cues[0].lines.single())
    }

    @Test
    fun `LRC 认不出时间戳要报错`() {
        val bad = assertThrows(Subtitles.Bad::class.java) {
            Subtitles.parse(SubtitleFormat.Lrc, "[ti:只有元数据]\n[ar:没有歌词]")
        }
        assertTrue(bad.message!!.contains("时间戳"), bad.message)
    }

    @Test
    fun `ASS 按文件自己的 Format 取列而不是硬编下标`() {
        // 老 SSA 会多一个 Marked 列；把"第 9、10 列"当常量写死的实现在这份上会整列错位
        val ass = """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: Marked=0,0:00:20.00,0:00:24.50,Default,,0,0,0,,{\fad(200,200)}第一行\N第二行
        """.trimIndent()
        val cues = Subtitles.parse(SubtitleFormat.Ass, ass)
        assertEquals(1, cues.size)
        assertEquals(20_000L, cues[0].startMs)
        assertEquals(24_500L, cues[0].endMs, "结尾是百分秒：50 就是 500 毫秒")
        assertEquals(listOf("第一行", "第二行"), cues[0].lines, "\\N 是换行，{...} 样式块要剥掉")
    }

    @Test
    fun `ASS 没有 Format 行就不猜列序`() {
        val bad = assertThrows(Subtitles.Bad::class.java) {
            Subtitles.parse(SubtitleFormat.Ass, "[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,x,,0,0,0,,一句")
        }
        assertTrue(bad.message!!.contains("Format"), bad.message)
    }

    @Test
    fun `时间轴下面没有正文时报出行号`() {
        val bad = assertThrows(Subtitles.Bad::class.java) {
            Subtitles.parse(SubtitleFormat.Srt, "1\n00:00:20,000 --> 00:00:24,400\n\n2\n")
        }
        assertEquals(2, (bad as Subtitles.Bad).line, "报错要指到那条时间轴的行号，800 条的文件里才有意义")
    }

    @Test
    fun `四种格式互转一圈，时间戳不能漂`() {
        val cues = Subtitles.parse(SubtitleFormat.Srt, srt)
        for (target in SubtitleFormat.entries) {
            val rendered = Subtitles.render(target, cues)
            val back = Subtitles.parse(target, rendered)
            val room = target.resolutionMs
            cues.zip(back).forEach { (before, after) ->
                assertTrue(
                    kotlin.math.abs(before.startMs - after.startMs) < room,
                    "${target.name} 的开始时间漂了 ${before.startMs - after.startMs}ms，格式上限是 ${room}ms",
                )
                if (target.keepsEndTime) {
                    assertTrue(
                        kotlin.math.abs(before.endMs - after.endMs) < room,
                        "${target.name} 的结束时间漂了 ${before.endMs - after.endMs}ms",
                    )
                } else {
                    // LRC 没有结束时间这一栏：转回来只能是"开始 + 默认停留"，
                    // 这不是 bug，但必须被 losses() 说出来，界面才能提醒用户
                    assertEquals(after.startMs + Subtitles.LRC_DEFAULT_MS, after.endMs)
                }
            }
            assertEquals(cues.map { target.expectedText(it) }, back.map { it.text },
                "${target.name} 的正文一个字都不能丢（LRC 一行放不下的多行会并成一行）")
        }
        // 转一圈再转回去必须稳定（幂等）：来回转会漂时间的实现都是错的
        val vtt = Subtitles.render(SubtitleFormat.Vtt, cues)
        assertEquals(vtt, Subtitles.render(SubtitleFormat.Vtt, Subtitles.parse(SubtitleFormat.Vtt, vtt)))
        val ass = Subtitles.render(SubtitleFormat.Ass, cues)
        assertEquals(ass, Subtitles.render(SubtitleFormat.Ass, Subtitles.parse(SubtitleFormat.Ass, ass)))
    }

    @Test
    fun `渲染前排序，序号连续`() {
        val messy = listOf(Cue(5_000, 6_000, listOf("后")), Cue(1_000, 2_000, listOf("先")))
        val rendered = Subtitles.render(SubtitleFormat.Srt, messy)
        assertTrue(rendered.indexOf("先") < rendered.indexOf("后"), "输出必须按时间排")
        val cues = Subtitles.parse(SubtitleFormat.Srt, rendered)
        assertEquals(listOf(1_000L, 5_000L), cues.map { it.startMs })
        assertEquals(listOf("1", "2"), rendered.lines().filter { it == "1" || it == "2" })
    }

    @Test
    fun `源格式先信扩展名再信内容`() {
        assertEquals(SubtitleFormat.Srt to null, Subtitles.detect("电影.srt", srt).let { it.first to it.second })
        // 扩展名说 srt、内容是 VTT：按内容判，并把"扩展名说的是什么"一起带出去
        val vtt = """
            WEBVTT

            00:00:20.000 --> 00:00:24.400
            字幕
        """.trimIndent()
        val found = Subtitles.detect("电影.srt", vtt)
        assertEquals(SubtitleFormat.Vtt, found.first)
        assertEquals(SubtitleFormat.Srt, found.second, "要能报出扩展名与内容不一致")
        // 扩展名没帮上忙时靠内容认
        assertEquals(SubtitleFormat.Srt, Subtitles.detect("无名", srt).first)
    }

    @Test
    fun `两边都对不上时报错带上两边的说法`() {
        val bad = assertThrows(Subtitles.Bad::class.java) { Subtitles.detect("x.srt", "这根本不是什么字幕") }
        assertTrue(bad.message!!.contains("SRT"), bad.message)
        assertTrue(bad.message!!.contains("解不通"), bad.message)
    }

    @Test
    fun `格式自己说清会丢什么`() {
        val cues = Subtitles.parse(SubtitleFormat.Srt, srt)
        val lrc = SubtitleFormat.Lrc.losses(cues)
        assertTrue(lrc.any { it.contains("结束时间") }, lrc.toString())
        assertTrue(lrc.any { it.contains("多行") }, lrc.toString())
        assertTrue(SubtitleFormat.Vtt.losses(cues).isEmpty(), "SRT 转 VTT 不丢东西")
        // 精度只在该格式真的会截时才报
        assertTrue(SubtitleFormat.Lrc.losses(listOf(Cue(20_000, 24_000, listOf("整十毫秒")))).none { it.contains("精度") })
        assertTrue(SubtitleFormat.Lrc.losses(listOf(Cue(20_005, 24_005, listOf("有毫秒零头")))).any { it.contains("精度") })
    }

    @Test
    fun `问题清单只报不拦`() {
        val cues = listOf(
            Cue(10_000, 5_000, listOf("倒挂")),
            Cue(0, 20_000, listOf("覆盖全场")),
            Cue(15_000, 18_000, listOf("和上一条重叠")),
            Cue(30_000, 31_000, listOf("  ")),
        )
        val problems = Subtitles.problems(cues)
        assertTrue(problems.any { it.contains("结束时间比开始还早") }, problems.toString())
        assertTrue(problems.any { it.contains("重叠") }, problems.toString())
        assertTrue(problems.any { it.contains("没有正文") }, problems.toString())
    }

    @Test
    fun `一位小数与三位小数不按字面读成毫秒`() {
        assertEquals(20_500L, Subtitles.parseTime("00:00:20.5", 1), ".5 是半秒")
        assertEquals(20_050L, Subtitles.parseTime("00:00:20.05", 1), ".05 是 50 毫秒")
        assertEquals(20_005L, Subtitles.parseTime("00:00:20.005", 1), ".005 才是 5 毫秒")
        assertEquals(20_000L, Subtitles.parseTime("00:00:20", 1), "没有小数也认")
    }
}
