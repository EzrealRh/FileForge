package com.fileforge.core

import com.fileforge.core.archive.ArchivePlan
import com.fileforge.core.archive.Gzip
import com.fileforge.core.archive.Tar
import com.fileforge.core.archive.TarItem
import com.fileforge.core.archive.UnpackPlan
import com.fileforge.core.archive.ZipEntry
import com.fileforge.core.archive.ZipReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * tar 与 gzip 的读与写。
 *
 * 判据盯着两件最容易悄悄错的事：**偏移**（一条的长度算错，后面每条都跟着错位，
 * 而 tar 里没有任何东西能告警）与**长名字的三种写法**（认不全就是"打开来少几个文件"）。
 * 头块按字节手搓，不拿自家写出来的那份自测 —— 自洽不等于对。
 */
class TarTest {

    private val nulSpace = String(charArrayOf(0.toChar(), ' '))

    /** 手搓一个 512 字节的头块，字段位置照 POSIX 那张表。 */
    private fun header(
        name: String,
        size: Long,
        type: Char = '0',
        prefix: String = "",
        mtime: Long = 1_700_000_000L,
        mode: Int = 420,
        link: String = "",
        checksumOff: Long = 0,
    ): ByteArray {
        val block = ByteArray(Tar.BLOCK)
        put(block, 0, name)
        octal(block, 100, 8, mode.toLong())
        octal(block, 108, 8, 0)
        octal(block, 116, 8, 0)
        octal(block, 124, 12, size)
        octal(block, 136, 12, mtime)
        block[156] = type.code.toByte()
        put(block, 157, link)
        put(block, 257, "ustar")
        put(block, 263, "00")
        put(block, 265, "tester")
        put(block, 345, prefix)
        // 算校验和之前先把那一格抹成空格：读的一方就是按"这 8 格是空格"算的
        put(block, 148, "        ")
        put(block, 148, "%06o".format(checksum(block) + checksumOff) + nulSpace)
        return block
    }

    private fun put(block: ByteArray, at: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        bytes.copyInto(block, at, 0, bytes.size.coerceAtMost(block.size - at))
    }

    private fun octal(block: ByteArray, at: Int, size: Int, value: Long) {
        put(block, at, "%${size - 1}o".format(value))
    }

    private fun checksum(block: ByteArray): Long =
        (0 until Tar.BLOCK).sumOf { index ->
            if (index in 148 until 156) ' '.code else block[index].toInt() and 0xFF
        }.toLong()

    /** 头块 + 内容（按 512 补齐）。 */
    private fun part(vararg pieces: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        pieces.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun padded(body: ByteArray): ByteArray =
        part(body, ByteArray((512 - body.size % 512) % 512))

    private fun names(bytes: ByteArray): List<String> = Tar.read(bytes).entries.map { it.name }

    private fun pax(vararg pairs: Pair<String, String>): ByteArray {
        val body = pairs.joinToString("") { (key, value) ->
            val record = "$key=$value\n"
            var declared = record.toByteArray(Charsets.UTF_8).size + 3
            while (declared.toString().length + 1 + record.toByteArray(Charsets.UTF_8).size != declared) declared++
            "$declared $record"
        }.toByteArray(Charsets.UTF_8)
        return part(header("pax", body.size.toLong(), 'x'), padded(body))
    }

    // ---- 读 ---------------------------------------------------------------------

    @Test
    fun `名字与内容按头块里的长度走，第二条不会错位`() {
        val bytes = part(
            header("甲.txt", 5), padded("一二三".toByteArray(Charsets.UTF_8).copyOf(5)),
            header("乙.txt", 3), padded("abc".toByteArray()),
        )
        val read = Tar.read(bytes)
        assertEquals(listOf("甲.txt", "乙.txt"), read.entries.map { it.name })
        assertEquals(listOf(5L, 3L), read.entries.map { it.size })
        assertEquals("abc", String(Tar.dataOf(read.entries[1], ZipReader.slicing(bytes)), Charsets.UTF_8))
    }

    @Test
    fun `ustar 的 prefix 字段要接回名字前面`() {
        val bytes = part(
            header("第二段.txt", 0, prefix = "很深的一层目录/还有一层"),
            header("x.txt", 0),
        )
        assertEquals("很深的一层目录/还有一层/第二段.txt", Tar.read(bytes).entries.first().name)
    }

    @Test
    fun `GNU 的长名字条目给下一条用，本身不算文件`() {
        val long = "目录/" + "很长的名字".repeat(20) + ".txt"
        val bytes = part(
            header("././@LongLink", long.toByteArray(Charsets.UTF_8).size.toLong() + 1, 'L'),
            padded(long.toByteArray(Charsets.UTF_8) + byteArrayOf(0)),
            header(long.take(99), 2), padded("hi".toByteArray()),
        )
        val read = Tar.read(bytes)
        assertEquals(1, read.entries.size, "长名字那条不该被当成一个文件")
        assertEquals(long, read.entries.first().name)
    }

    @Test
    fun `POSIX 扩展头里的 path 与 size 覆盖头块`() {
        val long = "中文目录/" + "名字".repeat(60) + ".txt"
        val bytes = part(
            pax("path" to long, "size" to "7"),
            header(long.take(90), 5),           // 头块里那份名字是截断的、长度也是旧的
            padded("一二三四五".toByteArray(Charsets.UTF_8).copyOf(7)),
            header("后面一条.txt", 2), padded("ok".toByteArray()),
        )
        val read = Tar.read(bytes)
        assertEquals(listOf(long, "后面一条.txt"), read.entries.map { it.name })
        assertEquals(7L, read.entries.first().size)
    }

    @Test
    fun `八进制放不下的数按二进制补码读`() {
        val big = header("巨大.bin", 0)
        // 首字节最高位置 1 = 二进制写法，剩下 11 字节是大端
        big[124] = 0x80.toByte()
        for (index in 125 until 134) big[index] = 0
        big[134] = 0x01
        big[135] = 0x02
        val old = header("老时间.bin", 0, mtime = 0)
        // 全 1 的补码 = -1，也就是 1970 之前（有些归档工具给老文件就这么写）
        for (index in 136 until 148) old[index] = 0xFF.toByte()
        val read = Tar.read(part(reseal(big), padded(ByteArray(258)), reseal(old)))
        assertEquals(258L, read.entries[0].size)
        assertEquals(-1L, read.entries[1].mtime)
    }

    /** 改过数字字段以后校验和必须重算：先抹成空格，再按"这 8 格是空格"算。 */
    private fun reseal(block: ByteArray): ByteArray {
        val copy = block.copyOf()
        put(copy, 148, "        ")
        put(copy, 148, "%06o".format(checksum(copy)) + nulSpace)
        return copy
    }

    @Test
    fun `头块校验和对不上就停下来说清楚，不接着错位读`() {
        val bytes = part(
            header("第一.txt", 2), padded("hi".toByteArray()),
            header("坏的那条", 2, checksumOff = 9), padded("..".toByteArray()),
            header("第三.txt", 2), padded("ok".toByteArray()),
        )
        val read = Tar.read(bytes)
        assertEquals(listOf("第一.txt"), read.entries.map { it.name })
        assertTrue(read.notes.any { it.contains("校验和") }, read.notes.toString())
    }

    @Test
    fun `认不出的类型字符不当成普通文件`() {
        val bytes = part(header("怪.txt", 2, type = 'K'), padded("hi".toByteArray()))
        val read = Tar.read(bytes)
        assertEquals("类型字符是 'K'，认不出来", read.entries.first().skipReason)
    }

    // ---- 写 ---------------------------------------------------------------------

    private fun item(name: String, body: String, mtime: Long = 1_700_000_000L) = TarItem(
        name = name,
        size = body.toByteArray(Charsets.UTF_8).size.toLong(),
        mtime = mtime,
    ) { ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) }

    @Test
    fun `写出去的包自己读得回来，名字长度与时间一个都不改`() {
        val names = listOf(
            "短.txt", "目录/深一点/甲.txt",
            "ASCII/" + "deep".repeat(30) + "/乙.txt",
            "中文/" + "很长的名字".repeat(30) + ".txt",
        )
        val bytes = Tar.write(names.map { item(it, "内容-$it") })
        val read = Tar.read(bytes)
        assertEquals(names, read.entries.map { it.name })
        assertEquals(names.map { "内容-$it".toByteArray(Charsets.UTF_8).size.toLong() }, read.entries.map { it.size })
        read.entries.forEach { assertEquals(1_700_000_000L, it.mtime, it.name) }
        read.entries.forEach {
            assertEquals("内容-${it.name}", String(Tar.dataOf(it, ZipReader.slicing(bytes)), Charsets.UTF_8))
        }
        assertTrue(read.notes.isEmpty(), read.notes.toString())
    }

    @Test
    fun `写的时候长度对不上就报错，不产出错位的包`() {
        val bad = TarItem("骗人的.txt", size = 99L, mtime = 0L) {
            ByteArrayInputStream("三个字".toByteArray(Charsets.UTF_8))
        }
        val error = runCatching { Tar.write(listOf(bad)) }.exceptionOrNull()
        assertTrue(error is IllegalStateException, "$error")
        assertTrue(error!!.message!!.contains("声明"), error.message!!)
    }

    @Test
    fun `目录条目没有内容，收尾两个零块就够了`() {
        val bytes = Tar.write(
            listOf(
                TarItem("一层/", size = 0, mtime = 10L, directory = true) { ByteArrayInputStream(ByteArray(0)) },
                item("一层/甲.txt", "甲"),
            ),
        )
        val read = Tar.read(bytes)
        assertEquals(listOf("一层/", "一层/甲.txt"), read.entries.map { it.name })
        assertTrue(read.entries.first().isDirectory)
        assertEquals(bytes.size % 512, 0)
    }

    // ---- 解压决策（与 zip 共用一套）------------------------------------------------

    private fun entry(name: String, type: Char) =
        Tar.read(part(header(name, 0, type))).entries.first()

    @Test
    fun `目录不当文件解，链接与设备跳过并说清为什么`() {
        val bytes = part(
            header("一层/", 0, '5'),
            header("甲.txt", 2), padded("hi".toByteArray()),
            header("指个链接", 0, '2', link = "/etc/passwd"),
            header("设备节点", 0, '3'),
        )
        val plan = ArchivePlan.plan(Tar.read(bytes)) as UnpackPlan.Go
        assertEquals(listOf("甲.txt"), plan.keep.map { it.name })
        val reasons = plan.skipped.map { it.second }
        assertEquals(2, reasons.size)
        assertTrue(reasons.any { it.contains("符号链接") }, reasons.toString())
        assertTrue(reasons.any { it.contains("字符设备") }, reasons.toString())
    }

    @Test
    fun `只有目录的包与空包都直说不做`() {
        assertTrue(
            (ArchivePlan.plan(Tar.read(part(header("一层/", 0, '5')))) as UnpackPlan.Refused)
                .reason.contains("只有目录"),
        )
        assertTrue((ArchivePlan.plan(Tar.read(ByteArray(0))) as UnpackPlan.Refused).reason.contains("都没有"))
    }

    @Test
    fun `声明体积超上限就整包拒，不解到一半再炸`() {
        val block = header("巨大.bin", 0)
        octal(block, 124, 12, ArchivePlan.MAX_TOTAL_BYTES + 1)
        val reason = (ArchivePlan.plan(Tar.read(part(reseal(block)))) as UnpackPlan.Refused).reason
        assertTrue(reason.contains("上限"), reason)
    }

    @Test
    fun `zip 与 tar 对同一种条目给同一条理由`() {
        // 符号链接在两种容器里都得拒，理由的话术也要一致：界面是同一行说明
        val zip = ZipEntry("指哪儿", 0, 0, 0, 0, 4L, 0L, 0L, 0xA1FF shl 16)
        val tar = entry("指哪儿", '2')
        assertTrue(zip.skipReason!!.contains("符号链接"), zip.skipReason!!)
        assertTrue(tar.skipReason!!.contains("符号链接"), tar.skipReason!!)
        assertEquals(zip.skipReason, tar.skipReason)
    }

    // ---- gzip -------------------------------------------------------------------

    @Test
    fun `头里的原始文件名是单个 gz 该叫什么的依据`() {
        val head = Gzip.readHeader(gzipHead(flags = 8, name = "报告/第一季度.txt"))
        assertEquals("报告/第一季度.txt", head?.name)
        assertTrue(head!!.supported)
        assertNull(Gzip.readHeader("这不是 gzip".toByteArray(Charsets.UTF_8)))
        // 压缩方式不是 DEFLATE（比如 .z 那种 LZW/compress）要直说解不动，别硬当空文件
        val other = gzipHead(flags = 0, name = null, method = 1)
        assertFalse(Gzip.readHeader(other)!!.supported)
        // 带 FEXTRA 的包：先跳过那一段才够得着文件名
        val withExtra = part(
            gzipHead(flags = 4 + 8, name = null),
            byteArrayOf(2, 0),
            "长文件名.txt".toByteArray(Charsets.UTF_8) + byteArrayOf(0),
        )
        assertEquals("长文件名.txt", Gzip.readHeader(withExtra)?.name)
    }

    @Test
    fun `我们写的 gzip 交给 JDK 的库读，倒过来也一样`() {
        val body = "中文与数字 12345\n".repeat(500).toByteArray(Charsets.UTF_8)
        val ours = ByteArrayOutputStream().also { out ->
            Gzip.gzip(ByteArrayInputStream(body), out, mtime = 1_700_000_123L, name = "重复.txt")
        }.toByteArray()
        // 反方向 1：JDK 的读取器看我们写的
        val fromJdk = java.util.zip.GZIPInputStream(ByteArrayInputStream(ours)).readBytes()
        assertTrue(fromJdk.contentEquals(body), "JDK 读不动我们写的 gzip")
        // 反方向 2：JDK 的写入器产出的，我们的头部解析与解体都得认（用完必须关掉，不然尾部没写完）
        val theirs = ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(body) }
        }.toByteArray()
        val back = ByteArrayOutputStream()
        assertEquals(body.size.toLong(), Gzip.ungzip(ByteArrayInputStream(theirs), back))
        assertTrue(back.toByteArray().contentEquals(body))
        assertNull(Gzip.readHeader(theirs)?.name, "JDK 不写文件名，我们也不该编一个")
    }

    @Test
    fun `尾部那两个数错了就要报错，不给一份安静的坏文件`() {
        val body = "六个字六个字".toByteArray(Charsets.UTF_8)
        val ours = ByteArrayOutputStream().also { out -> Gzip.gzip(ByteArrayInputStream(body), out) }
            .toByteArray()
        ours[ours.size - 5] = (ours[ours.size - 5] + 1).toByte()      // ISIZE 差一位
        val error = runCatching { Gzip.ungzip(ByteArrayInputStream(ours), ByteArrayOutputStream()) }
            .exceptionOrNull()
        assertTrue(error != null, "改坏了长度与 CRC 却没报错")
    }

    private fun gzipHead(flags: Int, name: String?, method: Int = 8): ByteArray {
        val head = byteArrayOf(0x1F, 0x8B.toByte(), method.toByte(), flags.toByte(), 0, 0, 0, 0, 0, 3)
        val body = name?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        return part(head, body, if (name == null) ByteArray(0) else byteArrayOf(0))
    }
}



