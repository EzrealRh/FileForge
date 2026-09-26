package com.fileforge.core

import com.fileforge.core.gif.GifCanvasPlan
import com.fileforge.core.model.BatchLineage
import com.fileforge.core.model.DayGroup
import com.fileforge.core.model.FileKind
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.Operation
import com.fileforge.core.ops.OperationKind
import com.fileforge.core.pdf.PageGroups
import com.fileforge.core.pdf.PageRangeException
import com.fileforge.core.pdf.PdfCompressPlan
import com.fileforge.core.pdf.PageRangeParser
import com.fileforge.core.pdf.SplitPlanner
import com.fileforge.core.text.SubtitleFormat
import com.fileforge.core.text.TextEncoding
import com.fileforge.core.util.SizeInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SizeInputTest {

    @Test
    fun `无单位按 MB 解析`() {
        assertEquals(10L * SizeInput.MEGA, SizeInput.parse("10"))
        assertEquals(1536L * SizeInput.KILO, SizeInput.parse("1.5MB"))
        assertEquals(500L * SizeInput.KILO, SizeInput.parse(" 500k "))
        assertEquals(2L * SizeInput.GIGA, SizeInput.parse("2G"))
        assertEquals(3L * SizeInput.MEGA, SizeInput.parse("3兆"))
    }

    @Test
    fun `非法输入返回 null 而不是崩`() {
        assertNull(SizeInput.parse(""))
        assertNull(SizeInput.parse("abc"))
        assertNull(SizeInput.parse("0"))
        assertNull(SizeInput.parse("-5"))
        assertNull(SizeInput.parse("10X"))
    }

    @Test
    fun `格式化给人看`() {
        assertEquals("512 B", SizeInput.format(512))
        assertEquals("1 KB", SizeInput.format(1024))
        assertEquals("1.5 MB", SizeInput.format(1536 * 1024))
    }
}

class PageRangeParserTest {

    private fun pages(spec: String, total: Int) = PageRangeParser.toPageIndices(PageRangeParser.parse(spec), total)

    @Test
    fun `多段范围按用户给的顺序展开成 0-based 页索引`() {
        assertEquals(listOf(99, 100, 199), pages("100-101,200", 300))
    }

    @Test
    fun `各种分隔符都认`() {
        val expected = listOf(0, 1, 2, 9)
        assertEquals(expected, pages("1-3,10", 10))
        assertEquals(expected, pages("1-3，10", 10))
        assertEquals(expected, pages("1-3; 10", 10))
        assertEquals(expected, pages("1-3、10", 10))
        assertEquals(expected, pages("1-3 10", 10))
    }

    @Test
    fun `开放端点贴到文档首尾`() {
        assertEquals(listOf(9, 10, 11), pages("10-", 12))
        assertEquals(listOf(0, 1, 2), pages("-3", 12))
    }

    @Test
    fun `倒着写就是倒序取页`() {
        assertEquals(listOf(2, 1, 0), pages("3-1", 5))
    }

    @Test
    fun `重复写几遍就重复出几页`() {
        assertEquals(listOf(0, 4, 0), pages("1,5,1", 5))
    }

    @Test
    fun `越界直接报错不悄悄裁剪`() {
        val error = assertThrows(PageRangeException::class.java) { pages("100-150", 120) }
        assertTrue(error.message!!.contains("150"), error.message)
    }

    @Test
    fun `非数字报错并指出是哪一段`() {
        val error = assertThrows(PageRangeException::class.java) { pages("10-abc", 120) }
        assertTrue(error.message!!.contains("abc"), error.message)
    }

    @Test
    fun `空输入报错`() {
        assertThrows(PageRangeException::class.java) { pages("   ", 120) }
    }
}

class SplitPlannerTest {

    private class Probe(private val pageBytes: Long) {
        var calls = 0
        fun measure(pages: List<Int>): Long {
            calls++
            check(pages.isNotEmpty())
            return pages.size * pageBytes
        }
    }

    @Test
    fun `每组都不超目标体积`() {
        val probe = Probe(1_000_000L)
        val groups = SplitPlanner(10_000_000L, probe::measure).plan(95)
        assertEquals(10, groups.size)
        assertTrue(groups.all { it.size * 1_000_000L <= 10_000_000L })
        assertEquals((0 until 95).toList(), groups.flatten())
    }

    @Test
    fun `尾部不足目标体积的剩余页单独成文件`() {
        val probe = Probe(1_000_000L)
        val groups = SplitPlanner(10_000_000L, probe::measure).plan(23)
        assertEquals(listOf(10, 10, 3), groups.map { it.size })
    }

    @Test
    fun `页数正好整除时不会多出一个空文件`() {
        val probe = Probe(1_000_000L)
        val groups = SplitPlanner(10_000_000L, probe::measure).plan(20)
        assertEquals(listOf(10, 10), groups.map { it.size })
    }

    @Test
    fun `单页本身就超标时只能自己一份`() {
        val probe = Probe(30_000_000L)
        val groups = SplitPlanner(10_000_000L, probe::measure).plan(3)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups)
    }

    @Test
    fun `只有一页时输出一份`() {
        val probe = Probe(1L)
        assertEquals(listOf(listOf(0)), SplitPlanner(10_000_000L, probe::measure).plan(1))
    }

    @Test
    fun `用指数扩张加二分 试探次数远小于页数`() {
        val probe = Probe(1_000_000L)
        val groups = SplitPlanner(100_000_000L, probe::measure).plan(1000)
        assertEquals(10, groups.size)
        assertTrue(probe.calls < 300, "measure 调用了 ${probe.calls} 次")
    }

    @Test
    fun `页面大小不均时也按实际体积切`() {
        val sizes = listOf(5L, 1L, 1L, 8L, 2L, 2L, 2L, 9L)
        val groups = SplitPlanner(10L) { pages -> pages.sumOf { sizes[it] } }.plan(sizes.size)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4), listOf(5, 6), listOf(7)), groups)
        assertTrue(groups.all { it.sumOf { sizes[it] } <= 10L })
    }
}

class OutputNamingTest {

    @Test
    fun `分割件按总份数决定补零宽度`() {
        assertEquals("报告_part01.pdf", OutputNaming.part("报告.pdf", 1, 9, "pdf"))
        assertEquals("报告_part009.pdf", OutputNaming.part("报告.pdf", 9, 100, "pdf"))
        assertEquals("报告_part100.pdf", OutputNaming.part("报告.pdf", 100, 100, "pdf"))
    }

    @Test
    fun `重名时补序号`() {
        assertEquals("a (2).jpg", OutputNaming.unique("a.jpg", setOf("a.jpg")))
        assertEquals("a (3).jpg", OutputNaming.unique("a.jpg", setOf("a.jpg", "a (2).jpg")))
        assertEquals("b.jpg", OutputNaming.unique("b.jpg", setOf("a.jpg")))
    }

    @Test
    fun `去掉文件系统不允许的字符但保留中文`() {
        assertEquals("报告1", OutputNaming.sanitize("报告/1<>:\"|?*"))
        assertEquals("file", OutputNaming.sanitize("***"))
    }

    @Test
    fun `取名字主干和扩展名`() {
        assertEquals("photo", OutputNaming.stem("photo.JPG"))
        assertEquals("tar", OutputNaming.extension("a.tar"))
        assertEquals("pdf", OutputNaming.extension("noext"))
    }
}

class PageGroupsTest {

    @Test
    fun `十页拆三份是四三三`() {
        assertEquals(
            listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)),
            PageGroups.evenSized(10, 3),
        )
    }

    @Test
    fun `份数比页数多就自动降到页数`() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), PageGroups.evenSized(3, 5))
    }

    @Test
    fun `一页的文档拆不出多份`() {
        assertEquals(listOf(listOf(0)), PageGroups.evenSized(1, 4))
    }

    @Test
    fun `任何份数都不重不漏按顺序覆盖全部页`() {
        for (total in 1..23) {
            for (parts in 1..12) {
                val groups = PageGroups.evenSized(total, parts)
                assertEquals((0 until total).toList(), groups.flatten(), "总页数 $total 拆 $parts 份")
                assertTrue(groups.all { it.isNotEmpty() }, "不该有空份：$groups")
                assertTrue(groups.zipWithNext().all { (a, b) -> a.last() + 1 == b.first() }, "份之间要连着：$groups")
            }
        }
    }

    @Test
    fun `非法输入直接拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { PageGroups.evenSized(0, 2) }
        assertThrows(IllegalArgumentException::class.java) { PageGroups.evenSized(10, 0) }
    }
}

class PdfCompressPlanTest {

    @Test
    fun `档位越靠后越紧`() {
        val ladder = PdfCompressPlan.ladder
        assertTrue(ladder.zipWithNext().all { (lo, hi) -> hi.maxEdge < lo.maxEdge && hi.quality < lo.quality })
    }

    @Test
    fun `按目标体积时从所选档往下走到底`() {
        assertEquals(4, PdfCompressPlan.tiersFrom(0).size)
        assertEquals(listOf(1000, 700), PdfCompressPlan.tiersFrom(2).map { it.maxEdge })
        assertEquals(1, PdfCompressPlan.tiersFrom(3).size)
    }

    @Test
    fun `档位号越界只夹到两端不抛异常`() {
        assertEquals(PdfCompressPlan.ladder.first(), PdfCompressPlan.tier(-5))
        assertEquals(PdfCompressPlan.ladder.last(), PdfCompressPlan.tier(99))
    }
}

class DayGroupTest {

    private fun at(text: String): Long =
        java.time.LocalDateTime.parse(text).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `今天昨天按自然日算不是按24小时`() {
        val now = at("2026-09-22T08:00:00")
        assertEquals(DayGroup.Today, DayGroup.of(at("2026-09-22T00:05:00"), now))
        assertEquals(DayGroup.Yesterday, DayGroup.of(at("2026-09-21T23:55:00"), now))
        // 24 小时之内但跨了天，也要算昨天
        assertEquals(DayGroup.Yesterday, DayGroup.of(at("2026-09-21T09:00:00"), now))
    }

    @Test
    fun `本周从周一算起，但今天昨天优先于本周`() {
        // 2026-09-21 是周一、09-22 是周二：同属本周，但"昨天"更精确，所以先归昨天
        val tuesday = at("2026-09-22T12:00:00")
        assertEquals(DayGroup.Yesterday, DayGroup.of(at("2026-09-21T08:00:00"), tuesday))
        // 上周日已经出周了
        assertEquals(DayGroup.Earlier, DayGroup.of(at("2026-09-20T08:00:00"), tuesday))
        // 2026-09-25 是周五，本周二是"本周"（既不是今天也不是昨天）
        val friday = at("2026-09-25T12:00:00")
        assertEquals(DayGroup.Week, DayGroup.of(at("2026-09-22T08:00:00"), friday))
        // 周一也在这一周内，所以它才是本周；上周五才算更早
        assertEquals(DayGroup.Week, DayGroup.of(at("2026-09-21T08:00:00"), friday))
        assertEquals(DayGroup.Earlier, DayGroup.of(at("2026-09-18T08:00:00"), friday))
        // 2026-09-20 是周日：本周只有它自己，前一天就出周了
        val sunday = at("2026-09-20T12:00:00")
        assertEquals(DayGroup.Today, DayGroup.of(at("2026-09-20T01:00:00"), sunday))
        assertEquals(DayGroup.Yesterday, DayGroup.of(at("2026-09-19T01:00:00"), sunday))
    }

    @Test
    fun `时间倒挂或同一天都算今天`() {
        val now = at("2026-09-22T12:00:00")
        assertEquals(DayGroup.Today, DayGroup.of(now, now))
        assertEquals(DayGroup.Today, DayGroup.of(at("2026-09-25T12:00:00"), now))
    }
}

class BatchLineageTest {

    @Test
    fun `来源同批就继承`() {
        assertEquals(7L, BatchLineage.inherit(listOf(7L, 7L, 7L)))
        assertEquals(7L, BatchLineage.inherit(listOf(BatchLineage.NONE, 7L)))
    }

    @Test
    fun `跨批或没批就另起一批`() {
        assertNull(BatchLineage.inherit(listOf(7L, 8L)))
        assertNull(BatchLineage.inherit(emptyList()))
        assertNull(BatchLineage.inherit(listOf(BatchLineage.NONE)))
    }
}

class GifCanvasPlanTest {

    @Test
    fun `画布是所有图的外接矩形`() {
        val canvas = GifCanvasPlan.canvas(
            widths = listOf(1000, 800, 500),
            heights = listOf(700, 1200, 500),
            pixelBudget = 100_000_000,
        )
        assertEquals(1000 to 1200, canvas)
    }

    @Test
    fun `最长边超了按比例缩，两边一起收`() {
        assertEquals(300 to 600, GifCanvasPlan.canvas(listOf(1000, 1000), listOf(2000, 2000), maxEdge = 600, pixelBudget = 100_000_000))
    }

    @Test
    fun `帧数乘像素超上限时按面积往下收`() {
        val budget = 14_000_000
        val canvas = GifCanvasPlan.canvas(List(100) { 2000 }, List(100) { 2000 }, pixelBudget = budget)
        // sqrt(14e6 / (2000*2000*100)) = 0.18708…，两边各取 374
        assertEquals(374 to 374, canvas)
        assertTrue(GifCanvasPlan.fits(canvas.first, canvas.second, 100, budget))
    }

    @Test
    fun `收到最小边还是装不下时如实说不兼容`() {
        val budget = 14_000_000
        val canvas = GifCanvasPlan.canvas(List(5000) { 4000 }, List(5000) { 4000 }, pixelBudget = budget)
        assertEquals(53 to 53, canvas)
        assertFalse(GifCanvasPlan.fits(canvas.first, canvas.second, 5000, budget))
    }

    @Test
    fun `一张图也能出画布`() {
        assertEquals(640 to 360, GifCanvasPlan.canvas(listOf(640), listOf(360), pixelBudget = 1 shl 20))
    }
}

class CrossConversionCatalogTest {

    @Test
    fun `GIF 可以转图片但不给普通格式转换`() {
        val gif = OperationKind.applicable(setOf(FileKind.Gif))
        assertTrue(OperationKind.GifToImages in gif)
        assertTrue(OperationKind.CompressGif in gif)
        assertFalse(OperationKind.ConvertImage in gif)
    }

    @Test
    fun `图片能合成 GIF，视频能抽帧`() {
        assertTrue(OperationKind.ImagesToGif in OperationKind.applicable(setOf(FileKind.Png, FileKind.Jpeg)))
        assertTrue(OperationKind.VideoToImage in OperationKind.applicable(setOf(FileKind.WebM)))
    }

    @Test
    fun `GIF 与 PDF 混选时只剩两条打包`() {
        // GIF 和 PDF 没有共同的转换操作；活下来的只有两条打包 —— 把不相干的两类一起装进一个包本来就是压缩包的用途
        // 本来就是压缩包的用途，所以它不属于"半可用按钮"
        assertEquals(listOf(OperationKind.PackZip, OperationKind.PackTar), OperationKind.applicable(setOf(FileKind.Gif, FileKind.Pdf)))
    }

    @Test
    fun `清元数据只给 JPEG 和 PNG，别的支持不了的容器不给入口`() {
        assertTrue(OperationKind.CleanMetadata in OperationKind.applicable(setOf(FileKind.Jpeg)))
        assertTrue(OperationKind.CleanMetadata in OperationKind.applicable(setOf(FileKind.Png)))
        // 混选一张 GIF 就该把入口拿掉，不然批量跑起来必然有一半报错
        assertFalse(OperationKind.CleanMetadata in OperationKind.applicable(setOf(FileKind.Png, FileKind.Gif)))
        for (kind in listOf(FileKind.WebP, FileKind.Bmp, FileKind.Heic, FileKind.Avif, FileKind.Gif)) {
            assertFalse(OperationKind.CleanMetadata in OperationKind.applicable(setOf(kind)), "$kind 还没做，不该给入口")
        }
    }

    @Test
    fun `打包对类型不设限，解压只给 zip`() {
        // 打包是"什么都能塞进去"的：把发票 pdf 和照片一起发人是常见诉求
        assertTrue(OperationKind.PackZip in OperationKind.applicable(setOf(FileKind.Pdf, FileKind.Jpeg, FileKind.Unknown)))
        assertTrue(OperationKind.PackZip in OperationKind.applicable(setOf(FileKind.Zip)))
        assertTrue(OperationKind.UnpackZip in OperationKind.applicable(setOf(FileKind.Zip)))
        assertFalse(OperationKind.UnpackZip in OperationKind.applicable(setOf(FileKind.Pdf)), "不是 zip 就别给解压按钮")
        assertFalse(
            OperationKind.UnpackZip in OperationKind.applicable(setOf(FileKind.Zip, FileKind.Pdf)),
            "混选时不能给一个必然半失败的按钮",
        )
    }
}

class OperationLabelTest {

    @Test
    fun `数据格式三个操作只摆在文本上`() {
        val text = OperationKind.applicable(setOf(FileKind.Text))
        listOf(
            OperationKind.FormatJson, OperationKind.JsonToCsv, OperationKind.CsvToJson,
            OperationKind.XmlToJson, OperationKind.JsonToXml,
        ).forEach {
            assertTrue(it in text, "${'$'}{it.label} 该摆在文本上")
        }
        // 压缩包与 PDF 上不许出现：选了必失败
        listOf(FileKind.Zip, FileKind.Pdf, FileKind.Jpeg).forEach { kind ->
            val ops = OperationKind.applicable(setOf(kind))
            assertFalse(OperationKind.FormatJson in ops, "$kind 上不该有 JSON 格式化")
            assertFalse(OperationKind.CsvToJson in ops, "$kind 上不该有 CSV 转 JSON")
        }
    }

    @Test
    fun `操作名要真把参数拼进去，不能留下没展开的模板`() {
        // 界面按钮上写的就是这些字符串，`${target.label}` 没展开会直接糊在用户脸上
        listOf(
            Operation.ConvertTextEncoding(target = TextEncoding.Gbk, bom = true),
            Operation.ConvertTextEncoding(target = TextEncoding.Utf8),
            Operation.ConvertSubtitle(SubtitleFormat.Vtt),
            Operation.EncryptPdf("1234"),
            Operation.DecryptPdf("1234"),
            Operation.FormatJson(pretty = true, indent = 4),
            Operation.FormatJson(pretty = false),
            Operation.JsonToCsv(),
            Operation.CsvToJson(header = false),
            Operation.UnpackArchive,
            Operation.PackArchive,
            Operation.XmlToJson(),
            Operation.JsonToXml(root = "catalog"),
        ).forEach {
            assertFalse(it.label.contains('$'), "「${it.label}」里有没展开的模板")
            assertFalse(it.label.contains('{'), "「${it.label}」里有没展开的模板")
        }
        assertEquals("转成 GBK（简体中文）（带 BOM）", Operation.ConvertTextEncoding(target = TextEncoding.Gbk, bom = true).label)
        assertEquals("转成 UTF-8", Operation.ConvertTextEncoding(target = TextEncoding.Utf8).label)
        assertEquals("字幕转为 WebVTT", Operation.ConvertSubtitle(SubtitleFormat.Vtt).label)
        // 这几条的名字里带参数，最容易在改文案时把占位符漏出来
        assertEquals("按 4 空格缩进", Operation.FormatJson(pretty = true, indent = 4).label)
        assertEquals("压成一行", Operation.FormatJson(pretty = false).label)
        assertEquals("转为 JSON（按数组摆）", Operation.CsvToJson(header = false).label)
        assertEquals("转为 JSON", Operation.XmlToJson().label)
        assertEquals("转为 XML", Operation.JsonToXml().label)
    }
}
