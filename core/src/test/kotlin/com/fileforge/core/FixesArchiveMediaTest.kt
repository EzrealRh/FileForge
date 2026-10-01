package com.fileforge.core

import com.fileforge.core.archive.Gzip
import com.fileforge.core.archive.Tar
import com.fileforge.core.audio.WavHeader
import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.doc.DocTable
import com.fileforge.core.gif.GifCanvasPlan
import com.fileforge.core.gif.GifDecoder
import com.fileforge.core.gif.GifException
import com.fileforge.core.gif.Lzw
import com.fileforge.core.office.RtfRead
import com.fileforge.core.office.RtfWrite
import com.fileforge.core.ops.VideoFormat
import com.fileforge.core.pdf.TextFit
import com.fileforge.core.pdf.TextLayoutPlanner
import com.fileforge.core.video.MuxSupport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * 一批**修过的 bug 的回归钉子**：每条对应一处曾经坏过的行为，断言的是修好之后的样子。
 *
 * 手搓字节的那几条（tar 头、GIF 块结构）照 `TarTest`、`GifTest.kt` 的老规矩：不拿自家写出的
 * 东西自测，字段位置与校验和按规范那张表逐字节摆。
 */
class FixesArchiveMediaTest {

    // ---- gzip：尾部 8 字节要写在关流之前 -----------------------------------------

    /** 手头只有几 KB 的重复文本，压缩才有实际内容可写，尾部才会排在 DEFLATE 流之后。 */
    private fun gzContent(): ByteArray = "文件工坊的转换说明。\n".repeat(300).toByteArray(Charsets.UTF_8)

    @Test
    fun `gzip 写进关了就炸的流，尾部先落地内容按字节回来`(@TempDir dir: File) {
        // 真实的 FileOutputStream 在 close 之后再 write 会抛 IOException：
        // 曾经的写法把 deflate 流一关就把目标流带走了，尾部 8 字节 CRC/ISIZE 没处写
        val file = File(dir, "原名.txt.gz")
        val content = gzContent()
        FileOutputStream(file).use { target ->
            Gzip.gzip(ByteArrayInputStream(content), target, name = "原名.txt")
        }
        val back = ByteArrayOutputStream()
        FileInputStream(file).use { source ->
            assertEquals(content.size.toLong(), Gzip.ungzip(source, back))
        }
        assertTrue(back.toByteArray().contentEquals(content), "解回来的内容必须按字节一致")
        assertEquals("原名.txt", Gzip.readHeader(file.readBytes())?.name)
    }

    // ---- tar：头块里的负数长度与截断的 PAX 记录 -----------------------------------

    private val nulSpace = String(charArrayOf(0.toChar(), ' '))

    private fun put(block: ByteArray, at: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        bytes.copyInto(block, at, 0, bytes.size.coerceAtMost(block.size - at))
    }

    /** 头块自己的校验和：那 8 个字节当空格算，其余按无符号字节相加。 */
    private fun checksum(block: ByteArray): Long =
        (0 until Tar.BLOCK).sumOf { index ->
            if (index in 148 until 156) ' '.code else block[index].toInt() and 0xFF
        }.toLong()

    private fun reseal(block: ByteArray): ByteArray {
        val copy = block.copyOf()
        put(copy, 148, "        ")
        put(copy, 148, "%06o".format(checksum(copy)) + nulSpace)
        return copy
    }

    private fun tarHeader(name: String, size: Long, type: Char = '0'): ByteArray {
        val block = ByteArray(Tar.BLOCK)
        put(block, 0, name)
        put(block, 100, "%7o".format(420))
        put(block, 108, "%7o".format(0))
        put(block, 116, "%7o".format(0))
        put(block, 124, "%11o".format(size))
        put(block, 136, "%11o".format(1_700_000_000L))
        block[156] = type.code.toByte()
        put(block, 257, "ustar")
        put(block, 263, "00")
        put(block, 148, "        ")
        put(block, 148, "%06o".format(checksum(block)) + nulSpace)
        return block
    }

    private fun part(vararg pieces: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        pieces.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun padded(body: ByteArray): ByteArray =
        part(body, ByteArray((Tar.BLOCK - body.size % Tar.BLOCK) % Tar.BLOCK))

    @Test
    @Timeout(5)
    fun `头块的内容长度是二进制补码负数时停下来说清，不再打转`() {
        // AT_SIZE(124) 起 12 字节全 1：二进制补码读出来是 -1。
        // 修好之前负的步长让读取原地打转，这里拿 5 秒超时看着它停。
        val block = tarHeader("坏长度.bin", 0)
        for (index in 124 until 136) block[index] = 0xFF.toByte()
        val read = Tar.read(part(reseal(block)))
        assertTrue(read.entries.isEmpty(), "负长度之后什么都读不得：${read.entries}")
        assertTrue(read.notes.any { it.contains("负") }, read.notes.toString())
    }

    @Test
    fun `pax 记录声明的长度超出剩余正文时整条跳过，不越界`() {
        // 记录自己声称 30 字节，正文只剩 14：硬按声明切就 StringIndexOutOfBoundsException
        val body = "30 path=short\n".toByteArray(Charsets.UTF_8)
        val bytes = part(
            tarHeader("PaxHeaders.0/entry", body.size.toLong(), 'x'),
            padded(body),
            ByteArray(Tar.BLOCK),
        )
        val read = Tar.read(bytes)
        assertTrue(read.entries.isEmpty())
        assertTrue(read.notes.isEmpty(), read.notes.toString())
    }

    // ---- GIF：disposal 3 的快照时机与画布像素预算 ---------------------------------

    private fun subBlocks(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        while (at < data.size) {
            val take = minOf(255, data.size - at)
            out.write(take)
            out.write(data, at, take)
            at += take
        }
        out.write(0)
        return out.toByteArray()
    }

    /**
     * 手搓一张两帧 GIF：2x1 画布、两色全局调色板（0 号黑、1 号绿）。
     * 第一帧铺满画布、图形控制扩展的 disposal 由 [firstFrameFlags] 指定；
     * 第二帧（disposal 0）只画右边一格。
     */
    private fun twoFrameGif(firstFrameFlags: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(2, 0, 1, 0, 0x80.toByte(), 0, 0))          // 逻辑屏幕 2x1，带 2 项全局调色板
        out.write(byteArrayOf(0, 0, 0, 0x00, 0xFF.toByte(), 0x00))       // 调色板：0 号黑、1 号绿
        // GCE：21 F9 04 <标志> <延时小端> <透明索引> 00；disposal 占标志的第 2~4 位
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, firstFrameFlags.toByte(), 0, 0, 0, 0))
        out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 1, 0, 0))          // 第一帧：(0,0) 2x1
        out.write(2)                                                     // LZW 初始码长
        out.write(subBlocks(Lzw.encode(intArrayOf(1, 1), 2, 2)))         // 两格都画 1 号绿
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 0, 0, 0, 0))    // 第二帧 GCE：disposal 0、无透明
        out.write(byteArrayOf(0x2C, 1, 0, 0, 0, 1, 0, 1, 0, 0))          // 第二帧：(1,0) 1x1
        out.write(2)
        out.write(subBlocks(Lzw.encode(intArrayOf(0), 1, 2)))            // 右格画 0 号黑
        out.write(0x3B)
        return out.toByteArray()
    }

    @Test
    fun `disposal 3 的快照赶在合成之前，下一帧回到画这帧之前的样子`() {
        // flags = 0b011 shl 2 = 0x0C，解码出来 disposal = 3
        val decoded = GifDecoder.decode(twoFrameGif(firstFrameFlags = 0x0C))
        assertEquals(2, decoded.frames.size)
        val green = 0xFF00FF00.toInt()
        val black = 0xFF000000.toInt()
        assertTrue(decoded.frames[0].argb.contentEquals(intArrayOf(green, green)), "第一帧应铺满绿")
        // 第二帧只画右边一格：左边那格必须回到画第一帧**之前**的透明，
        // 快照拍晚了拍到的就是第一帧自己，恢复等于没恢复（修好之前的 bug）
        assertEquals(0, decoded.frames[1].argb[0], "第一帧画过的区域要被恢复，不能残留绿色")
        assertEquals(black, decoded.frames[1].argb[1], "第二帧自己画的那格还在")
    }

    @Test
    fun `disposal 0 与 1 不恢复画布，第一帧的像素留给第二帧`() {
        listOf(0x00, 0x04).forEach { flags ->                       // disposal 0 与 1
            val decoded = GifDecoder.decode(twoFrameGif(firstFrameFlags = flags))
            assertEquals(2, decoded.frames.size)
            assertEquals(0xFF00FF00.toInt(), decoded.frames[1].argb[0], "disposal ${flags shr 2} 的左格要保留第一帧")
            assertEquals(0xFF000000.toInt(), decoded.frames[1].argb[1])
        }
    }

    @Test
    fun `画布声明尺寸超像素预算时立刻报错，不开数组`() {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(100, 0, 100, 0, 0x80.toByte(), 0, 0))  // 逻辑屏幕声明 100x100，带 2 项调色板
        out.write(byteArrayOf(0, 0, 0, 0, 0, 0))
        out.write(0x3B)
        val error = assertThrows(GifException::class.java) {
            GifDecoder.decode(out.toByteArray(), pixelBudget = 999)
        }
        val message = error.message!!
        assertTrue(message.contains("预算") || message.contains("像素"), message)
    }

    // ---- 音频与封装 ---------------------------------------------------------------

    @Test
    fun `float 的 WAV 头在偏移 20 写 3，pcm16 仍写 1，超 32 位长度的 PCM 直接拒绝`() {
        val float = WavHeader.of(1000L, 44100, 2, 32, formatTag = WavHeader.FORMAT_IEEE_FLOAT)
        assertEquals(3, float[20].toInt() and 0xFF, "格式标签在偏移 20：float 得写 3，不然放出来是噪音")
        assertEquals(0, float[21].toInt() and 0xFF)
        val pcm = WavHeader.pcm16(1000L, 44100, 2)
        assertEquals(1, pcm[20].toInt() and 0xFF, "整数 PCM 仍写 1")
        // 32 位长度字段装不下就直说，不给一份头尾对不上的安静坏文件
        val error = assertThrows(IllegalArgumentException::class.java) {
            WavHeader.pcm16(0x1_0000_0000L, 44100, 2)
        }
        assertTrue(error.message!!.contains("装不下"), error.message)
        assertEquals(44, WavHeader.pcm16(0xFFFFFFF0L - 36, 44100, 2).size, "边界内（装得下的最大值）不拒绝")
    }

    @Test
    fun `音轨 mime 带参数与大小写时归一化后再比对`() {
        // MediaExtractor 报的 mime 可能带参数：不剥掉的话收得下的音轨被误判成收不下
        assertTrue(MuxSupport.keepsAudio(VideoFormat.Mp4, "audio/mp4a-latm; profile=1"))
        assertTrue(MuxSupport.keepsAudio(VideoFormat.Mp4, "AUDIO/MP4A-LATM; Profile=1"))
        assertFalse(MuxSupport.keepsAudio(VideoFormat.Mp4, "audio/raw"))
    }

    // ---- RTF：表格列边缘与跳过块里的 \bin ------------------------------------------

    @Test
    fun `表格每列的右边缘一个数比一个大，不再三个 500`() {
        val doc = Doc(
            listOf(DocTable(false, listOf(listOf("甲", "乙", "丙"), listOf("1", "2", "3")))),
            emptyList(),
        )
        val written = String(RtfWrite.document(doc).bytes, Charsets.US_ASCII)
        val at500 = written.indexOf("\\cellx500")
        val at1000 = written.indexOf("\\cellx1000")
        val at1500 = written.indexOf("\\cellx1500")
        assertTrue(at500 >= 0 && at1000 > at500 && at1500 > at1000, "三列该是 \\cellx500/1000/1500 递增：$written")
    }

    @Test
    fun `跳过的目的群里 bin 的原始字节不当记号，后面的正文活着`() {
        // \bin4 之后是 4 个原始字节 7B 5C 7D 7B（{ \ } {）：当成 RTF 走状态机就会
        // 多开两组收不回来，"done" 被吞进跳过的组里；按二进制跳过才轮得到正文
        val bytes = "{\\rtf1\\ansi{\\*\\exotic\\bin4{\\}{}}done}".toByteArray(Charsets.ISO_8859_1)
        val read = RtfRead.read(bytes)
        val texts = read.doc.parts.filterIsInstance<DocParagraph>().map { it.para.text }
        assertEquals(listOf("done"), texts)
        assertTrue(read.notes.any { it.contains("二进制") }, read.notes.toString())
    }

    // ---- 排版与水印字号、合成画布 ---------------------------------------------------

    /** 西文 6 单位、其他（CJK）12 单位，按字号线性缩放（与 TextLayoutTest 同一把尺子）。 */
    private fun widthOf(text: String, size: Float): Float {
        var units = 0
        text.forEach { ch -> units += if (TextLayoutPlanner.isCjk(ch)) 2 else 1 }
        return units * 6f * size / 12f
    }

    private fun layout(text: String, pageWidth: Float) =
        TextLayoutPlanner.layout(text, pageWidth, 842f, 56f, 12f, 1.4f, 0f, ::widthOf)

    @Test
    fun `断行把开括号推下去时按 raw 算退回量，括号不丢行不错位`() {
        // 内容宽 24pt、每字 6pt："ab (" 恰好装满，开括号得推到下一行。
        // 退回的字符数曾按去空白后的 line 算，括号和空白一起被跳过 —— 那个字符哪一行都不在
        val lines = layout("ab ( c", pageWidth = 24f + 56f * 2)
            .pages.flatMap { page -> page.lines.map { it.text } }
        assertEquals("ab ( c", lines.joinToString(""), "断完拼回去必须一字不差：$lines")
        assertTrue(lines.none { it.endsWith("(") }, "开括号不许留在行尾：$lines")
        assertEquals(1, lines.joinToString("").count { it == '(' }, "括号一个不多一个不少：$lines")
    }

    @Test
    fun `maxSize 比 minSize 还小时按 minSize 兜底，不抛区间错误`() {
        // 水印两字各算 1 个方块单位：100 / 2 = 50，但可用区间 [6, 3] 是反的 ——
        // 修好之前 coerceIn 直接 IllegalArgumentException，整次操作崩掉
        val size = TextFit.largestFontSize("水印", 100f, maxSize = 3f)
        assertTrue(size.isFinite())
        assertEquals(6f, size, 0.001f, "夹不进反的区间就落在 minSize 上")
    }

    @Test
    fun `源图比最小边还小时不放大，画布保持原尺寸`() {
        // 16x8 的源曾两边都被 coerceAtLeast(32) 扯到 32x32：说好只缩不放
        val canvas = GifCanvasPlan.canvas(listOf(16), listOf(8), pixelBudget = 1_000_000)
        assertEquals(16, canvas.first)
        assertEquals(8, canvas.second)
    }
}
