package com.fileforge.core

import com.fileforge.core.meta.ImageMeta
import com.fileforge.core.util.SizeInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * 图片元数据的查看与清理。
 *
 * 期望值全部来自 `meta` 目录下的 `.truth` 文件 —— 那是 Pillow 自己解析同一份文件后
 * 记下来的（标记清单、字段值、像素数据的 sha256），不是照我家解析器反推的。
 * 生成：`python tools/make_meta_fixtures.py`。
 */
class ImageMetaTest {

    private fun resource(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("meta/$name").use { input ->
            requireNotNull(input) { "缺少夹具 meta/$name，先跑 python tools/make_meta_fixtures.py" }
            ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
        }

    private fun truth(name: String): Map<String, String> =
        String(resource(name), Charsets.UTF_8).lines().filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 取文件里 SOS 之后的扫描数据（像素本体），用于证明清理没重编码。 */
    private fun jpegScan(bytes: ByteArray): ByteArray {
        val piece = ImageMeta.pieces(bytes).first { it.name == "SOS+图像数据" }
        // 段起点含 FF DA 与其长度头，跳过头部才是熵数据
        val length = ((bytes[piece.from + 2].toInt() and 0xFF) shl 8) or (bytes[piece.from + 3].toInt() and 0xFF)
        return bytes.copyOfRange(piece.from + 2 + length, piece.to)
    }

    // ---- 容器与段 -------------------------------------------------------------

    @Test
    fun `认容器只认前缀不认扩展名`() {
        assertEquals(ImageMeta.Container.Jpeg, ImageMeta.container(resource("photo.jpg")))
        assertEquals(ImageMeta.Container.Png, ImageMeta.container(resource("note.png")))
        assertNull(ImageMeta.container("这不是图片".toByteArray()))
        assertNull(ImageMeta.container(ByteArray(0)))
    }

    @Test
    fun `JPEG 段清单要和 PIL 看到的一致`() {
        val truth = truth("photo.jpg.truth")
        val names = ImageMeta.pieces(resource("photo.jpg")).map { it.name }
        val expected = mapOf("APP0" to "JFIF (APP0)", "APP1" to "APP1", "APP2" to "APP2", "COM" to "注释 (COM)")
        truth.getValue("markers").split(",").forEach { marker ->
            val want = expected.getValue(marker)
            assertTrue(want in names, "PIL 看到 $marker，我们没找到（段清单：$names）")
        }
    }

    @Test
    fun `保留影响显示的段丢掉带身份的段`() {
        val pieces = ImageMeta.pieces(resource("photo.jpg"))
        fun identifying(name: String) = pieces.first { it.name == name }.identifying
        // EXIF(APP1) 与注释(COM) 是身份信息
        assertTrue(identifying("APP1"), "APP1 装的是 EXIF/XMP，必须丢")
        assertTrue(identifying("注释 (COM)"), "COM 常放作者与版权，必须丢")
        // JFIF 只有密度与缩略偏移，ICC 和 Adobe 影响颜色 —— 删了照片会变色，不能算身份信息
        assertEquals(false, identifying("JFIF (APP0)"), "APP0 不含身份信息，保留")
        assertEquals(false, identifying("APP2"), "ICC 描述文件影响颜色，保留")
        assertTrue(pieces.first { it.name == "SOS+图像数据" }.let { !it.identifying }, "像素数据永远保留")
    }

    // ---- 读字段 ---------------------------------------------------------------

    @Test
    fun `EXIF 字段读出来要和 PIL 报的一致`() {
        val truth = truth("photo.jpg.truth")
        val report = ImageMeta.report(resource("photo.jpg"))
        val byKey = report.fields.toMap()
        assertEquals(truth.getValue("make"), byKey["制造商"], "制造商要和 PIL 读到的一致")
        assertEquals(truth.getValue("model"), byKey["机型"], "机型")
        assertEquals(truth.getValue("date_time"), byKey["修改时间"], "修改时间")
        assertEquals(truth.getValue("date_time"), byKey["拍摄参数 拍摄时间"], "Exif 子 IFD 里的拍摄时间")
        assertEquals("200", byKey["拍摄参数 ISO"], "子 IFD 里的数值型标签")
    }

    @Test
    fun `GPS 读成一行位置`() {
        val truth = truth("photo.jpg.truth")
        val gps = ImageMeta.report(resource("photo.jpg")).gps
        assertNotNull(gps, "带 GPS 的夹具必须能报出位置")
        val line = gps!!
        listOf(30.0, 15.0, 30.0).forEach { assertTrue(line.contains(fmt(it)), "纬度分量 $it 没出现在「$line」") }
        listOf(120.0, 6.0).forEach { assertTrue(line.contains(fmt(it)), "经度分量 $it 没出现在「$line」") }
        assertTrue(line.contains("N") && line.contains("E"), "引用字母要在：$line")
    }

    private fun fmt(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    @Test
    fun `方向标签要读得出来，清掉之前要能提醒`() {
        // 手机拍的照片十张里大半靠 EXIF 方向站着。清掉 EXIF 而不说一声，用户看到的
        // 就是"照片躺下了"，而且再也改不回来（像素没动，方向信息已经没了）
        val truth = truth("photo.jpg.truth")
        val report = ImageMeta.report(resource("photo.jpg"))
        assertEquals(truth.getValue("orientation").toInt(), report.orientation, "方向要和 PIL 读到的一致")
        assertTrue(report.willLoseRotation, "非 1 档要在界面上提醒")
        assertEquals("6 顺时针九十度", report.fields.toMap()["方向"], "方向要写成看得懂的话，不能光一个 6")
        assertNull(ImageMeta.report(resource("note.png")).orientation, "PNG 没写方向就是没有")
        assertEquals(false, ImageMeta.report(resource("note.png")).willLoseRotation)
    }

    // ---- 清理 -----------------------------------------------------------------

    @Test
    fun `清理后像素一个字节都不动`() {
        val original = resource("photo.jpg")
        val truth = truth("photo.jpg.truth")
        val cleaned = ImageMeta.clean(original)
        assertNotNull(cleaned)
        assertEquals(truth.getValue("scan_sha"), sha256(jpegScan(cleaned!!)), "扫描数据必须逐字节相同")
        assertEquals(truth.getValue("scan_sha"), sha256(jpegScan(original)), "夹具的参照值本身要能对上")
        assertTrue(cleaned[0] == 0xFF.toByte() && cleaned[1] == 0xD8.toByte(), "还得是个 JPEG")
        val tail = cleaned.takeLast(2)
        assertEquals(0xD9.toByte(), tail[1], "要以 EOI 收尾")
        assertEquals(0xFF.toByte(), tail[0])
    }

    @Test
    fun `清理后不再有身份信息而 ICC 还在`() {
        val cleaned = ImageMeta.clean(resource("photo.jpg"))!!
        val pieces = ImageMeta.pieces(cleaned)
        assertTrue(pieces.none { it.identifying }, "还有身份段没清掉：${pieces.filter { it.identifying }.map { it.name }}")
        assertTrue(pieces.any { it.name == "APP2" }, "ICC 要留着，不然照片变色")
        assertTrue(pieces.any { it.name == "JFIF (APP0)" }, "APP0 要留着")
        assertNull(ImageMeta.report(cleaned).gps, "清完不该还读得出位置")
        // 再清一次没有可清的了：不产出重复成品，交给上层报"这张本来就没元数据"
        assertNull(ImageMeta.clean(cleaned))
    }

    @Test
    fun `PNG 清文本块但留 ICC 与像素`() {
        val original = resource("note.png")
        val truth = truth("note.png.truth")
        val pieces = ImageMeta.pieces(original)
        assertEquals(truth.getValue("chunks").split(","), pieces.filter { it.name != "签名" }.map { it.name })
        assertTrue(pieces.first { it.name == "tEXt" }.identifying)
        assertTrue(pieces.first { it.name == "iTXt" }.identifying)
        assertTrue(pieces.first { it.name == "eXIf" }.identifying)
        assertEquals(false, pieces.first { it.name == "iCCP" }.identifying, "ICC 影响颜色，保留")
        assertEquals(false, pieces.first { it.name == "IDAT" }.identifying, "像素数据保留")

        val cleaned = ImageMeta.clean(original)!!
        val names = ImageMeta.pieces(cleaned).map { it.name }
        assertTrue("iCCP" in names && "IHDR" in names && "IEND" in names, "该留的没留：$names")
        assertTrue(names.none { it in setOf("tEXt", "iTXt", "eXIf", "tIME") }, "该删的没删：$names")
        val idat = ImageMeta.pieces(cleaned).first { it.name == "IDAT" }.let { piece ->
            // 段区间含长度、类型、数据与 CRC；数据从第 8 字节起，长度是前 4 字节
            val length = chunkLength(cleaned, piece.from)
            cleaned.copyOfRange(piece.from + 8, piece.from + 8 + length)
        }
        assertEquals(truth.getValue("idat_sha"), sha256(idat), "PNG 的像素数据必须逐字节相同")
        // 块是整段照抄的，所以 CRC 没重算过也仍然有效
        assertEquals(0x49, cleaned[cleaned.size - 8].toInt(), "结尾还得是 IEND 的类型首字母")
    }

    private fun chunkLength(bytes: ByteArray, at: Int) =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    @Test
    fun `PNG 文本块要能在报告里看到`() {
        val report = ImageMeta.report(resource("note.png"))
        assertTrue(report.fields.any { it.first == "tEXt 文本块" }, report.fields.toString())
        assertTrue(report.lines.any { it.contains("Author") }, "键名要显示出来：${report.lines}")
        assertEquals("PngModelTest", report.fields.toMap()["机型"], "PNG 的 eXIf 块也得读")
    }

    @Test
    fun `残档不硬组`() {
        val truncated = resource("photo.jpg").copyOfRange(0, 700)     // 砍掉 EOI
        assertNull(ImageMeta.clean(truncated), "没有 EOI 就原样退回，别产出一份打不开的文件")
        val halfPng = resource("note.png").copyOfRange(0, 40)
        assertNull(ImageMeta.clean(halfPng), "没有 IEND 同理")
    }

    @Test
    fun `拒绝理由和实际清不清得动必须一致`() {
        // cleanBlocker 管界面文案、clean 管字节。两套判断一旦说不到一起，
        // 用户就会看到"能清"却报错，或者看到"清不了"却照样产出成品
        val cleaned = ImageMeta.clean(resource("photo.jpg"))!!
        val cases = listOf(
            resource("photo.jpg"),
            resource("note.png"),
            cleaned,
            ImageMeta.clean(resource("note.png"))!!,
            resource("photo.jpg").copyOfRange(0, 700),
            "这不是图片".toByteArray(),
            ByteArray(0),
        )
        cases.forEachIndexed { index, bytes ->
            val blocker = ImageMeta.report(bytes).cleanBlocker
            val produced = runCatching { ImageMeta.clean(bytes) }.getOrNull()
            assertEquals(blocker == null, produced != null, "第 $index 份：理由=$blocker，成品=$produced")
        }
        assertNull(ImageMeta.report(resource("photo.jpg")).cleanBlocker, "正常的 JPEG 应该报「可以清」")
        assertTrue(ImageMeta.report(cleaned).cleanBlocker!!.contains("没有"), "清过一次的要说没有可清的")
        assertTrue(ImageMeta.report(resource("photo.jpg").copyOfRange(0, 700)).cleanBlocker!!.contains("结束标记"))
        assertTrue(ImageMeta.report("x".toByteArray()).cleanBlocker!!.contains("PNG"))
    }

    @Test
    fun `清理前先说清要扔掉哪些段`() {
        val report = ImageMeta.report(resource("photo.jpg"))
        assertTrue(report.saving > 0)
        assertEquals(report.saving.toLong(), report.identifying.sumOf { it.bytes.toLong() })
        val note = report.removalNote
        assertTrue(note.contains("APP1"), "清单里要能看到 APP1：$note")
        assertTrue(note.contains("注释 (COM)"), "清单里要能看到注释段：$note")
        assertTrue(!note.contains("APP2"), "ICC 不该出现在要扔的清单里：$note")
        // 每段都要带大小，不然用户不知道这一趟值不值
        report.identifying.forEach { assertTrue(note.contains(SizeInput.format(it.bytes.toLong())), "${it.name} 少了大小：$note") }
        assertEquals("没有可清理的身份信息", ImageMeta.report(resource("photo.jpg").let { ImageMeta.clean(it)!! }).removalNote)
    }

    @Test
    fun `前缀读取够详情页显示字段`() {
        val full = resource("photo.jpg")
        val file = java.io.File.createTempFile("fileforge-meta", ".jpg")
        try {
            file.writeBytes(full)
            assertEquals(full.toList(), ImageMeta.head(file).toList(), "比上限小就该整个读回来")
            assertEquals(8, ImageMeta.head(file, 8).size, "超上限要截住，不能把整份搬进堆")
            // 详情页只靠这份前缀报字段：字段和整份文件读出来的必须一模一样
            assertEquals(ImageMeta.report(full).fields, ImageMeta.report(ImageMeta.head(file)).fields)
            val png = java.io.File.createTempFile("fileforge-meta", ".png")
            try {
                png.writeBytes(resource("note.png").copyOfRange(0, 30))
                assertEquals(30, ImageMeta.head(png).size, "残档按实际长度返回，不能补零")
            } finally {
                png.delete()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `认不出的标记报十六进制而不是乱编一个 APP 号`() {
        // 手工搭一份：SOI + 一个没名字也带长度的段 + EOI
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xC3.toByte(), 0x00, 0x02,
            0xFF.toByte(), 0xD9.toByte(),
        )
        val names = ImageMeta.pieces(bytes).map { it.name }
        assertTrue("标记 0xC3" in names, "段清单：$names")
        assertTrue(names.none { it.startsWith("APP-") }, "不许出现 APP-14 这种话：$names")
        assertTrue(names.last() == "EOI")
    }

    @Test
    fun `清理产物落盘供第三方解码器复核`() {
        // 我家解析器认自己的输出不算数。这两个文件交给 tools/verify_meta_clean.py，
        // 用 Pillow（完全另一套实现）打开、比像素、确认 EXIF 没了 ICC 还在。
        val dir = java.io.File("build/meta-clean").apply { mkdirs() }
        listOf("photo.jpg", "note.png").forEach { name ->
            val original = resource(name)
            val cleaned = ImageMeta.clean(original) ?: error("$name 应该清得动")
            java.io.File(dir, "cleaned-$name").writeBytes(cleaned)
            java.io.File(dir, name).writeBytes(original)
        }
        assertTrue(java.io.File(dir, "cleaned-photo.jpg").length() > 0)
    }

    @Test
    fun `不支持的容器直接拒`() {
        val gif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0)
        assertNull(ImageMeta.container(gif))
        val error = runCatching { ImageMeta.clean(gif) }.exceptionOrNull()
        assertNotNull(error, "HEIC/GIF/WebP 没实现就要明确报错，不能拿 JPEG 的规则硬套")
        assertTrue(error!!.message!!.contains("JPEG"), error.message)
    }
}
