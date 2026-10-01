package com.fileforge.core

import com.fileforge.core.archive.ArchivePlan
import com.fileforge.core.archive.UnpackPlan
import com.fileforge.core.archive.ZipArchive
import com.fileforge.core.archive.ZipEntry
import com.fileforge.core.archive.ZipItem
import com.fileforge.core.archive.ZipReader
import com.fileforge.core.archive.ZipWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * zip 的读写。
 *
 * 参照物不是我自己：夹具由 **Python 标准库 zipfile** 生成（见 tools/make_archive_fixtures.py），
 * 写侧的产物也回交给 zipfile 打开核对。自家 writer 写出的包只有自家 reader 能读是没用的 ——
 * 用户要的恰恰是别的工具能打开。
 */
class ZipTest {

    private fun resource(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("archive/$name").use { input ->
            requireNotNull(input) { "缺少夹具 archive/$name，先跑 python tools/make_archive_fixtures.py" }
            ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
        }

    private fun sliceOf(name: String): ByteArray = resource(name)

    private fun truth(name: String): Map<String, String> =
        String(resource(name), Charsets.UTF_8).lines().filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    // ---- 读：拿 Python 压的包当参照 -----------------------------------------------

    @Test
    fun `Python 压的包能读出完整目录`() {
        val truth = truth("plain.zip.truth")
        val archive = ZipReader.read(resource("plain.zip"))
        assertEquals(truth.getValue("names").split("|"), archive.entries.map { it.name })
        assertEquals(truth.getValue("sizes").split("|").map { it.toLong() }, archive.entries.map { it.size })
        assertEquals(truth.getValue("methods").split("|").map { it.toInt() }, archive.entries.map { it.method })
    }

    @Test
    fun `解出来的内容与 Python 记下的字节数与 CRC 相符`() {
        val truth = truth("plain.zip.truth")
        val bytes = sliceOf("plain.zip")
        val archive = ZipReader.read(bytes)
        val crcs = truth.getValue("crcs").split("|").map { it.toLong() }
        val sizes = truth.getValue("sizes").split("|").map { it.toLong() }
        archive.entries.forEachIndexed { index, entry ->
            val data = ZipReader.dataOf(entry, bytes)
            assertEquals(crcs[index], CRC32().apply { update(data) }.value, "${entry.name} 的 CRC")
            assertEquals(sizes[index], data.size.toLong(), "${entry.name} 的长度")
        }
    }

    @Test
    fun `中文与日文名字都按 UTF-8 标志位读对`() {
        val archive = ZipReader.read(resource("names.zip"))
        assertEquals(
            listOf("说明.txt", "照片/猫.jpg", "日本語/テスト.txt"),
            archive.entries.map { it.name },
            "名字读错的话解压出来全是乱码",
        )
    }

    @Test
    fun `带注释的包要从尾部倒着找结束记录`() {
        // 写死"末尾 22 字节"的实现会在这一份上散架：EOCD 前面还挂着注释
        val archive = ZipReader.read(resource("comment.zip"))
        assertEquals(listOf("a.txt"), archive.entries.map { it.name })
        assertTrue(archive.comment.contains("注释"), "注释没读出来：${archive.comment}")
    }

    @Test
    fun `GBK 名字的包在没置 UTF-8 标志时也要认`() {
        val archive = ZipReader.read(resource("gbk.zip"))
        assertTrue(archive.entries.any { it.name == "中文文件.txt" }, "实际读到：${archive.entries.map { it.name }}")
    }

    // ---- 写：产物必须被 Python 认可 -----------------------------------------------

    @Test
    fun `自家写的包名字与 CRC 和 Python 压的一致`() {
        val truth = truth("written.zip.truth")
        val read = ZipReader.read(writtenZip())
        // 名字与 CRC 是跨实现必须一致的两项；"zipfile 能不能打开这份产物"由 verify_archive.py 验
        assertEquals(truth.getValue("names").split("|"), read.entries.map { it.name })
        assertEquals(truth.getValue("crcs").split("|").map { it.toLong() }, read.entries.map { it.crc })
        val alreadyZipped = read.entries[2]
        assertEquals(
            truth.getValue("methods").split("|").map { it.toInt() }, read.entries.map { it.method },
            "选哪种压缩方式两边必须走同一条规则，否则两条表早晚会漂开",
        )
        assertEquals(0, alreadyZipped.method, "名字带 .zip 的那条要按规则直接存原文")
        assertTrue(alreadyZipped.compressedSize <= alreadyZipped.size)
    }

    @Test
    fun `写出的包落盘交给 zipfile 复核`() {
        // 判据是"别人的实现打不打得开、解出来一不一样"，所以产物要交到 Python 那边去看
        // （tools/verify_archive.py）。载荷一并落盘，脚本按同样的字节比内容。
        val dir = java.io.File("build/archive").apply { mkdirs() }
        java.io.File(dir, "written.zip").writeBytes(writtenZip())
        listOf("item-note.bin", "item-blob.bin", "item-random.bin").forEach { name ->
            java.io.File(dir, name).writeBytes(sliceOf(name))
        }
        assertTrue(java.io.File(dir, "written.zip").length() > 0)
    }

    @Test
    fun `写完读回来内容一字不差`() {
        val bodies = listOf(
            "甲乙丙".toByteArray(),
            ByteArray(70_000) { (it % 7).toByte() },     // 跨多个 32KB 块，流式路径才算是真走通了
            ByteArray(0),
        )
        val items = listOf(
            ZipItem("a.txt", bodies[0], FIXED_TIME),
            ZipItem("b.bin", bodies[1], FIXED_TIME),
            ZipItem("empty.txt", bodies[2], FIXED_TIME),
        )
        val packed = ZipWriter.write(items)
        val archive = ZipReader.read(packed)
        bodies.forEachIndexed { index, body ->
            assertEquals(body.toList(), ZipReader.dataOf(archive.entries[index], packed).toList(), items[index].name)
        }
    }

    @Test
    fun `时间戳走一圈只丢秒以下的精度`() {
        val archive = ZipReader.read(ZipWriter.write(listOf(ZipItem("t.txt", "x".toByteArray(), FIXED_TIME))))
        val back = archive.entries.first().modifiedAt
        assertEquals(FIXED_TIME / 2000, back / 2000, "DOS 时间只有 2 秒精度，别的都不该变")
    }

    @Test
    fun `坏包要报坏而不是给半份内容`() {
        val good = ZipWriter.write(listOf(ZipItem("a.txt", "hello".toByteArray(), FIXED_TIME)))
        // 中央目录头里 CRC 在第 16 字节。改这里：内容看着仍能解出来，校验必须逮住
        val centralAt = good.indexOfSignature(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
        val tampered = good.copyOf().also { it[centralAt + 16] = (it[centralAt + 16].toInt() xor 0x5C).toByte() }
        val error = runCatching { ZipReader.dataOf(ZipReader.read(tampered).entries.first(), tampered) }.exceptionOrNull()
        assertNotNull(error, "CRC 被改掉必须报错")
        assertTrue(error!!.message!!.contains("校验"), error.message)
        // 反过来：本地头里的 CRC 是流式写入方常填错的那份，读侧一律不信它，所以改完照样能解
        val localTampered = good.copyOf().also { it[14] = (it[14].toInt() xor 0x5C).toByte() }
        assertEquals(
            "hello".toByteArray().toList(),
            ZipReader.dataOf(ZipReader.read(localTampered).entries.first(), localTampered).toList(),
            "判据只能有一个来源：既然以中央目录为准，本地头的坏值就不该影响结果",
        )
        assertTrue(
            runCatching { ZipReader.read(good.copyOfRange(0, good.size - 5)) }.exceptionOrNull()!!.message!!
                .contains("中央目录"),
            "尾巴被削掉一段，要报的是找不到目录",
        )
        assertTrue(
            runCatching { ZipReader.read(good.copyOfRange(0, 20)) }.exceptionOrNull()!!.message!!.contains("太小"),
            "小得连 EOCD 都放不下时，报的该是「太小」",
        )
    }

    private fun ByteArray.indexOfSignature(signature: ByteArray, from: Int = 0): Int {
        for (start in from..size - signature.size) {
            if (signature.indices.all { this[start + it] == signature[it] }) return start
        }
        error("找不到签名")
    }

    // ---- 写：zip64 ---------------------------------------------------------------
    //
    // 真实的门槛是 4 GB / 65535 条，单测够不着，所以把 ZipWriter 的两道门槛注水到几字节几条，
    // 让 zip64 的路径在小包上也能走通。判据照旧是双份的：自家 reader 要读得动，
    // JDK 的 ZipFile（另一套独立实现）也要认 —— 只有自家 reader 认的 zip64 是没用处的。

    @Test
    fun `单条尺寸超门槛时写 zip64 扩展且内容原样回来`() {
        withZip64Limits(sizeLimit = 100) {
            val body = ByteArray(200) { (it % 13).toByte() }
            val packed = ZipWriter.write(listOf(ZipItem("big.bin", body, FIXED_TIME)))
            // 本地头：扩展 20 字节（id + 长度 + 两条 8 字节），32 位长度让位给哨兵，version 也要抬到 45
            assertEquals(20, u16le(packed, 28), "本地头扩展长度")
            assertEquals(0xFFFFFFFFL, u32le(packed, 18), "本地头压缩后长度")
            assertEquals(0xFFFFFFFFL, u32le(packed, 22), "本地头原始长度")
            assertEquals(45, u16le(packed, 4), "本地头 version needed")
            // 中央目录：只有原始长度真超了门槛，压缩后与偏移照旧写 32 位，扩展里只挂真超的那枚
            val centralAt = packed.indexOfSignature(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
            assertEquals(45, u16le(packed, centralAt + 6), "中央 version needed")
            assertEquals(0xFFFFFFFFL, u32le(packed, centralAt + 24), "中央原始长度")
            assertNotEquals(0xFFFFFFFFL, u32le(packed, centralAt + 20), "压缩后没超门槛，不该上哨兵")
            assertEquals(12, u16le(packed, centralAt + 30), "中央扩展 = 头 4 字节 + 一枚 8 字节真值")
            assertEquals(200L, u64le(packed, centralAt + 46 + u16le(packed, centralAt + 28) + 4), "扩展头之后才是真值")
            // 三个读法都得认：自家 reader、JDK 的 ZipFile
            val archive = ZipReader.read(packed)
            assertEquals(body.toList(), ZipReader.dataOf(archive.entries.single(), packed).toList())
            javaZipFileRoundTrip(packed, "big.bin", body)
        }
    }

    @Test
    fun `直存条目两条长度都超门槛时真值按头序进扩展`() {
        withZip64Limits(sizeLimit = 100) {
            val body = ByteArray(300) { (it * 7).toByte() }
            val item = ZipItem("raw.bin", body.size.toLong(), FIXED_TIME, stored = true) { body.inputStream() }
            val packed = ZipWriter.write(listOf(item))
            // 直存的压缩后长度等于原始长度：两条都超门槛，中央扩展里按头序先原始后压缩，共 16 字节
            val centralAt = packed.indexOfSignature(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
            assertEquals(20, u16le(packed, centralAt + 30), "中央扩展长度")
            assertEquals(0xFFFFFFFFL, u32le(packed, centralAt + 20), "中央压缩后长度")
            assertEquals(0xFFFFFFFFL, u32le(packed, centralAt + 24), "中央原始长度")
            val bodyAt = centralAt + 46 + u16le(packed, centralAt + 28) + 4   // 跳过扩展头（id + 长度）
            assertEquals(300L, u64le(packed, bodyAt), "扩展第一枚是原始长度")
            assertEquals(300L, u64le(packed, bodyAt + 8), "扩展第二枚是压缩后长度")
            val archive = ZipReader.read(packed)
            assertEquals(300L, archive.entries.single().size)
            assertEquals(300L, archive.entries.single().compressedSize)
            assertEquals(body.toList(), ZipReader.dataOf(archive.entries.single(), packed).toList())
            javaZipFileRoundTrip(packed, "raw.bin", body)
        }
    }

    @Test
    fun `中央目录的头偏移超门槛时真值排在扩展最后`() {
        withZip64Limits(sizeLimit = 100) {
            val body = ByteArray(80) { (it % 5).toByte() }
            fun stored(name: String) =
                ZipItem(name, body.size.toLong(), FIXED_TIME, stored = true) { body.inputStream() }
            val packed = ZipWriter.write(listOf(stored("one.bin"), stored("two.bin")))
            // 第一条本地头 + 名字 + 数据占 117 字节，第二条的头偏移因此过了门槛，但两条的长度都没有
            val centralSig = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
            val second = packed.indexOfSignature(centralSig, packed.indexOfSignature(centralSig) + 4)
            assertEquals(12, u16le(packed, second + 30), "扩展里只该有一枚 8 字节偏移")
            assertEquals(0xFFFFFFFFL, u32le(packed, second + 42), "第二条的偏移要上哨兵")
            assertEquals(117L, u64le(packed, second + 46 + u16le(packed, second + 28) + 4), "扩展头之后才是偏移真值")
            assertEquals(0, u16le(packed, packed.indexOfSignature(centralSig) + 30), "第一条没超门槛，不该带扩展")
            val archive = ZipReader.read(packed)
            assertEquals(117L, archive.entries[1].localHeaderAt, "偏移得从扩展里原样读回来")
            javaZipFileRoundTrip(packed, "two.bin", body)
        }
    }

    @Test
    fun `条目数超门槛时落 EOCD64 与定位器且各方都读得到全部条目`() {
        withZip64Limits(countLimit = 2) {
            val bodies = listOf("甲".toByteArray(), "乙".toByteArray(), "丙".toByteArray())
            val packed = ZipWriter.write(bodies.mapIndexed { i, body -> ZipItem("$i.txt", body, FIXED_TIME) })
            // EOCD64 定长 56、定位器定长 20，之后只剩 22 字节的普通 EOCD；定位器要指回 EOCD64 的位置
            val recordAt = packed.indexOfSignature(byteArrayOf(0x50, 0x4B, 0x06, 0x06))
            val locatorAt = packed.indexOfSignature(byteArrayOf(0x50, 0x4B, 0x06, 0x07))
            assertEquals(56, locatorAt - recordAt, "EOCD64 与定位器之间不该有别的字节")
            assertEquals(locatorAt + 20 + 22, packed.size, "定位器之后应该只剩普通 EOCD")
            assertEquals(recordAt.toLong(), u64le(packed, locatorAt + 8), "定位器要指回 EOCD64")
            assertEquals(3L, u64le(packed, recordAt + 32), "条数真值在 EOCD64 里")
            // 门槛降到了 2：普通 EOCD 的条数必须让位给哨兵，否则读的人不知道该去看 EOCD64
            assertEquals(0xFFFF, u16le(packed, packed.size - 22 + 10), "普通 EOCD 的条数该是哨兵")
            val archive = ZipReader.read(packed)
            assertEquals(3, archive.entries.size)
            bodies.forEachIndexed { i, body ->
                assertEquals(body.toList(), ZipReader.dataOf(archive.entries[i], packed).toList())
            }
            val file = java.io.File("build/archive/zip64-count.zip").apply { parentFile.mkdirs(); writeBytes(packed) }
            java.util.zip.ZipFile(file).use { zf ->
                assertEquals(3, zf.size(), "JDK 得从 EOCD64 里数出全部条目")
                bodies.forEachIndexed { i, body ->
                    assertEquals(body.toList(), zf.getInputStream(zf.getEntry("$i.txt")).use { it.readBytes() }.toList())
                }
            }
        }
    }

    @Test
    fun `不超门槛的小包一个 zip64 字节都不该有`() {
        val packed = ZipWriter.write(
            listOf(ZipItem("a.txt", "内容".toByteArray(), FIXED_TIME), ZipItem("b.bin", ByteArray(50), FIXED_TIME)),
        )
        assertFalse(packed.containsSig(byteArrayOf(0x50, 0x4B, 0x06, 0x06)), "不该有 EOCD64")
        assertFalse(packed.containsSig(byteArrayOf(0x50, 0x4B, 0x06, 0x07)), "不该有定位器")
        // 顺着本地头链走一遍：扩展长度必须全 0，中央记录同理 —— 多写一个 0x0001 就有工具会看错
        var local = 0
        repeat(2) {
            assertEquals(0, u16le(packed, local + 28), "第 ${it + 1} 条本地头不该带扩展")
            local += 30 + u16le(packed, local + 26) + u16le(packed, local + 28) + u32le(packed, local + 18).toInt()
        }
        val centralSig = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
        val firstCentral = packed.indexOfSignature(centralSig)
        val secondCentral = packed.indexOfSignature(centralSig, firstCentral + 4)
        listOf(firstCentral, secondCentral).forEachIndexed { i, at ->
            assertEquals(0, u16le(packed, at + 30), "第 ${i + 1} 条中央记录不该带扩展")
            assertEquals(20, u16le(packed, at + 6), "version needed 该还是 20")
        }
        val archive = ZipReader.read(packed)
        assertEquals(2, archive.entries.size)
    }

    @Test
    fun `没预留扩展却真超了门槛要炸出来而不是写坏包`() {
        withZip64Limits(sizeLimit = 100) {
            // 谎报尺寸：item.size 说 10，真实字节 300 —— 本地头写完才发现 32 位装不下，回填已无扩展可补
            val liar = ZipItem("liar.bin", 10L, FIXED_TIME, stored = true) { ByteArray(300).inputStream() }
            val error = runCatching { ZipWriter.write(listOf(liar)) }.exceptionOrNull()
            assertTrue(error is IllegalStateException, "实际抛了：$error")
            assertTrue(error!!.message!!.contains("liar.bin"), "报错要能定位到条目：${error.message}")
        }
    }

    // ---- 决策 -------------------------------------------------------------------

    @Test
    fun `压平名字把上跳和目录一起消化掉`() {
        listOf(
            "../../etc/passwd" to "etc_passwd",
            "a/b/c.txt" to "a_b_c.txt",
            "/abs/top.txt" to "abs_top.txt",
            "C:/windows/x.txt" to "C__windows_x.txt",
            "x\\y\\z.png" to "x_y_z.png",
            "./just.txt" to "just.txt",
        ).forEach { (raw, want) ->
            assertEquals(want, ArchivePlan.flatten(raw), raw)
        }
    }

    @Test
    fun `压平后的名字不藏控制字符超长也保住扩展名`() {
        val weird = ArchivePlan.flatten("a\u0001b:c?.txt")
        assertEquals("a_b_c_.txt", weird)
        val long = ArchivePlan.flatten("目录/" + "长".repeat(200) + ".pdf")
        assertTrue(long.length <= 96, "长度没收住：${long.length}")
        assertTrue(long.endsWith(".pdf"), "截断把扩展名截没了就没法打开了：$long")
        assertEquals("未命名", ArchivePlan.flatten("///"))
    }

    @Test
    fun `口令包与陌生压缩方式整包或逐条拒`() {
        val encrypted = ZipArchive(listOf(entry("a.txt", encrypted = true)))
        assertTrue((ArchivePlan.plan(encrypted) as UnpackPlan.Refused).reason.contains("口令"))

        val zstd = ZipArchive(listOf(entry("a.txt"), entry("b.txt", method = 93)))
        val partial = ArchivePlan.plan(zstd) as UnpackPlan.Go
        assertEquals(listOf("a.txt"), partial.keep.map { it.name })
        assertTrue(partial.skipped.single().second.contains("zstd"), partial.skipped.toString())
        assertTrue(ArchivePlan.skippedNote(partial.skipped).contains("跳过 1 条"))

        val empty = ZipArchive(emptyList())
        assertTrue((ArchivePlan.plan(empty) as UnpackPlan.Refused).reason.contains("都没有"))

        val onlyDirs = ZipArchive(listOf(entry("d/", directory = true)))
        assertTrue((ArchivePlan.plan(onlyDirs) as UnpackPlan.Refused).reason.contains("只有目录"))
    }

    @Test
    fun `符号链接不解出`() {
        val link = entry("evil", externalAttributes = 0xA1FF shl 16)
        val plan = ArchivePlan.plan(ZipArchive(listOf(entry("ok.txt"), link))) as UnpackPlan.Go
        assertEquals(listOf("ok.txt"), plan.keep.map { it.name })
        assertTrue(plan.skipped.single().second.contains("符号链接"))
    }

    @Test
    fun `声明体积超过上限就整包不硬解`() {
        val huge = ZipArchive(listOf(entry("a.bin", size = ArchivePlan.MAX_TOTAL_BYTES + 1)))
        val reason = (ArchivePlan.plan(huge) as UnpackPlan.Refused).reason
        assertTrue(reason.contains("超过"), reason)
    }

    @Test
    fun `解出来的名字带包名前缀且总长有上限`() {
        assertEquals("旅行照片_照片_猫.jpg", ArchivePlan.outputName("旅行照片.zip", "照片/猫.jpg"))
        // 两个包里都有「读我.txt」时，靠包名前缀才不会互相盖掉
        assertNotEquals(
            ArchivePlan.outputName("a.zip", "读我.txt"),
            ArchivePlan.outputName("b.zip", "读我.txt"),
        )
        val long = ArchivePlan.outputName("很长的压缩包名字".repeat(6) + ".zip", "里面/" + "字".repeat(120) + ".txt")
        assertTrue(long.length <= 92, "名字长度没收住，MediaStore 会写入失败：${long.length}")
        assertTrue(long.endsWith(".txt"), "扩展名必须保住：$long")
        assertEquals("x_未命名.bin", ArchivePlan.outputName("x.zip", "///"))
    }

    @Test
    fun `包里同名条目在打包时编号而不是互相覆盖`() {
        assertEquals(
            listOf("a.txt", "a_2.txt", "a_3.txt", "b", "b_2", "未命名"),
            ArchivePlan.uniqueNames(listOf("a.txt", "a.txt", "a.txt", "b", "b", "")),
        )
        assertEquals(listOf("c.md", "c_2.md"), ArchivePlan.uniqueNames(listOf("c.md", "c.md")))
    }

    // ---- 小工具 -----------------------------------------------------------------

    private fun writtenZip(): ByteArray = ZipWriter.write(
        listOf(
            // 三份载荷直接读夹具旁边的 .bin：两边各写一遍定义，早晚会有一份是错的
            ZipItem("说明.txt", sliceOf("item-note.bin"), FIXED_TIME),
            ZipItem("sub/keep.bin", sliceOf("item-blob.bin"), FIXED_TIME),
            ZipItem("store/raw.zip", sliceOf("item-random.bin"), FIXED_TIME),
        ),
    )

    private fun entry(
        name: String,
        method: Int = 0,
        size: Long = 8L,
        encrypted: Boolean = false,
        directory: Boolean = false,
        externalAttributes: Int = 0x20,
    ) = ZipEntry(
        name = if (directory && !name.endsWith("/")) "$name/" else name,
        method = method,
        flags = if (encrypted) 0x1 else 0x800,
        crc = 0L,
        compressedSize = size,
        size = size,
        localHeaderAt = 0L,
        modifiedAt = FIXED_TIME,
        externalAttributes = externalAttributes,
    )

    /** 把 ZipWriter 的 zip64 门槛临时注水到测试够得着的量级，走完无论成败都还原。 */
    private fun <T> withZip64Limits(
        sizeLimit: Long = ZipWriter.zip64SizeLimit,
        countLimit: Long = ZipWriter.zip64EntryCountLimit,
        block: () -> T,
    ): T {
        val oldSize = ZipWriter.zip64SizeLimit
        val oldCount = ZipWriter.zip64EntryCountLimit
        ZipWriter.zip64SizeLimit = sizeLimit
        ZipWriter.zip64EntryCountLimit = countLimit
        return try {
            block()
        } finally {
            ZipWriter.zip64SizeLimit = oldSize
            ZipWriter.zip64EntryCountLimit = oldCount
        }
    }

    /** JDK 的 ZipFile 是另一套独立实现：它也读得动、解得对，才算真的跨过了实现的门槛。 */
    private fun javaZipFileRoundTrip(packed: ByteArray, name: String, body: ByteArray) {
        val file = java.io.File("build/archive/zip64-check-$name").apply { parentFile.mkdirs(); writeBytes(packed) }
        java.util.zip.ZipFile(file).use { zf ->
            val entry = requireNotNull(zf.getEntry(name)) { "ZipFile 里找不到 $name" }
            assertEquals(body.size.toLong(), entry.size, "$name 的尺寸")
            assertEquals(body.toList(), zf.getInputStream(entry).use { it.readBytes() }.toList(), name)
        }
    }

    private fun ByteArray.containsSig(signature: ByteArray): Boolean {
        for (start in 0..size - signature.size) {
            if (signature.indices.all { this[start + it] == signature[it] }) return true
        }
        return false
    }

    // zip 全栈小端，测试里拆字段用的几枚小起子

    private fun u16le(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32le(b: ByteArray, at: Int): Long =
        ((b[at + 3].toLong() and 0xFF) shl 24) or ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or (b[at].toLong() and 0xFF)

    private fun u64le(b: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0..7) value = value or ((b[at + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    private companion object {
        /** 2026-01-02 03:04:06 本地时间：秒取偶数，正好卡在 DOS 时间的 2 秒精度上。 */
        const val FIXED_TIME = 1_767_322_046_000L
    }
}
