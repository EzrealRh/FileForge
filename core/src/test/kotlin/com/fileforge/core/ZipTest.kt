package com.fileforge.core

import com.fileforge.core.archive.ArchivePlan
import com.fileforge.core.archive.UnpackPlan
import com.fileforge.core.archive.ZipArchive
import com.fileforge.core.archive.ZipEntry
import com.fileforge.core.archive.ZipItem
import com.fileforge.core.archive.ZipReader
import com.fileforge.core.archive.ZipWriter
import org.junit.jupiter.api.Assertions.assertEquals
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

    private fun ByteArray.indexOfSignature(signature: ByteArray): Int {
        for (start in 0..size - signature.size) {
            if (signature.indices.all { this[start + it] == signature[it] }) return start
        }
        error("找不到签名")
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

    private companion object {
        /** 2026-01-02 03:04:06 本地时间：秒取偶数，正好卡在 DOS 时间的 2 秒精度上。 */
        const val FIXED_TIME = 1_767_322_046_000L
    }
}
