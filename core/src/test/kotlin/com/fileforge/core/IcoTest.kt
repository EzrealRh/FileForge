package com.fileforge.core

import com.fileforge.core.data.Ico
import com.fileforge.core.data.IcoImage
import com.fileforge.core.data.IcoException
import com.fileforge.core.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ICO 图标。
 *
 * 夹具由 tools/make_ico_fixtures.py 造：DIB 那份是本脚本按微软的目录格式手工拼的
 * （因为 Pillow 只会写 PNG 内嵌），拼完先让 Pillow 打开确认拼对了才当参照用。
 */
class IcoTest {

    private fun resource(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("ico/$name").use { input ->
            requireNotNull(input) { "缺少夹具 ico/$name，先跑 python tools/make_ico_fixtures.py" }
            java.io.ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
        }

    private fun sample(): Json = Json.parse(String(resource("sample.json"), Charsets.UTF_8))

    private fun expected(side: Int): IntArray {
        val list = sample().field("argb$side")!!.arrayValue
        return IntArray(list.size) { list[it].stringValue!!.removePrefix("0x").toLong(16).toInt() }
    }

    // ---- 目录 -----------------------------------------------------------------

    @Test
    fun `两种内嵌方式的目录都读得对`() {
        listOf("dib32.ico", "pngico.ico").forEach { name ->
            val entries = Ico.directory(resource(name))
            assertEquals(2, entries.size, name)
            // 目录顺序按文件里怎么写就怎么读，不排序：Pillow 那份是 16 在前，手工拼的那份是 32 在前
            assertEquals(setOf(16, 32), entries.map { it.width }.toSet(), name)
            assertEquals(entries.map { it.width }, entries.map { it.height }, "方形图标宽高一致")
            assertTrue(entries.all { it.bitCount == 32 }, "$name 位深读错：${entries.map { it.bitCount }}")
        }
        assertEquals(listOf(16, 32), Ico.directory(resource("pngico.ico")).map { it.width }, "顺序要跟着目录走")
        assertEquals(listOf(32, 16), Ico.directory(resource("dib32.ico")).map { it.width }, "顺序要跟着目录走")
        val png = Ico.directory(resource("pngico.ico"))
        assertTrue(png.all { it.isPng }, "Pillow 写的那份内部是 PNG")
        assertTrue(Ico.directory(resource("dib32.ico")).none { it.isPng }, "手工拼的那份是 DIB")
    }

    @Test
    fun `256 的图标在目录里记成 0 也要读回 256`() {
        // 宽高是单字节，规范用 0 表示 256 —— 判错就会把最大的那张读成 0×0
        val bytes = Ico.write(listOf(IcoImage(256, 256, IntArray(256 * 256) { it })))
        val entry = Ico.directory(bytes).single()
        assertEquals(256, entry.width)
        assertEquals(256, entry.height)
        assertEquals(0, bytes[6].toInt(), "目录里就该写 0，否则别的工具读出来的尺寸不对")
    }

    // ---- 读 DIB ---------------------------------------------------------------

    @Test
    fun `DIB 解出来的像素与造夹具时的值逐格相同`() {
        val image = Ico.read(resource("dib32.ico")).first { it.width == 32 }
        val want = expected(32)
        assertEquals(want.size, image.argb.size)
        var mismatch = -1
        for (index in want.indices) {
            if (want[index] != image.argb[index]) { mismatch = index; break }
        }
        // 消息要留到真不一致时再拼：mismatch 是 -1 时按下标取值会先把断言自己炸掉
        if (mismatch >= 0) {
            throw AssertionError("第 $mismatch 格期望 ${Integer.toHexString(want[mismatch])} 实得 ${Integer.toHexString(image.argb[mismatch])}")
        }
        assertEquals(want.toList(), image.argb.toList(), "逐格比对没走完")
    }

    @Test
    fun `PNG 内嵌的那条交出原始字节而不是假装解出像素`() {
        val pngEntry = Ico.read(resource("pngico.ico")).first { it.isPngSide }
        assertNotNull(pngEntry.png, "PNG 内嵌的要把字节留给安卓的解码器")
        assertTrue(pngEntry.argb.all { it == 0 }, "没解像素就该全 0，不能拿假像素冒充")
    }

    private val IcoImage.isPngSide: Boolean get() = png != null

    // ---- 写 ------------------------------------------------------------------

    @Test
    fun `写完读回来是同一批像素`() {
        val source = expected(16)
        val bytes = Ico.write(listOf(IcoImage(16, 16, source)))
        val back = Ico.read(bytes).single()
        assertEquals(16, back.width)
        source.forEachIndexed { index, color ->
            val got = back.argb[index]
            // alpha 为 0 时颜色无意义（别家工具会按预乘洗掉），只比 alpha
            if (color ushr 24 == 0) assertEquals(0, got ushr 24, "第 $index 格透明度要留住")
            else assertEquals(color, got, "第 $index 格 ${Integer.toHexString(color)} 变成 ${Integer.toHexString(got)}")
        }
    }

    @Test
    fun `多尺寸一次写进一份文件`() {
        val images = listOf(16, 32, 48).map { side -> IcoImage(side, side, IntArray(side * side) { it }) }
        val bytes = Ico.write(images)
        assertEquals(listOf("16×16", "32×32", "48×48"), Ico.sizes(bytes))
        assertEquals(3, Ico.directory(bytes).size)
    }

    @Test
    fun `坏输入与不支持的形态要报清楚`() {
        assertTrue(runCatching { Ico.directory(byteArrayOf(1, 2, 3)) }.exceptionOrNull() is IcoException)
        assertTrue(runCatching { Ico.directory("这不是图标".toByteArray()) }.exceptionOrNull()!!.message!!.contains("ICO"))
        assertTrue(runCatching { Ico.write(emptyList()) }.exceptionOrNull()!!.message!!.contains("没有"))
        val tooBig = runCatching { Ico.write(listOf(IcoImage(300, 300, IntArray(300 * 300)))) }.exceptionOrNull()
        assertNotNull(tooBig, "超过 256 的要拒")
    }

    @Test
    fun `产物落盘交给 Pillow 复核`() {
        val dir = java.io.File("build/ico").apply { mkdirs() }
        java.io.File(dir, "written.ico").writeBytes(
            Ico.write(listOf(IcoImage(32, 32, expected(32)), IcoImage(16, 16, expected(16)))),
        )
        val one: IcoImage = Ico.read(resource("dib32.ico")).first { it.width == 32 }
        java.io.File(dir, "resaved.ico").writeBytes(Ico.write(listOf(one)))
        // Pillow 一份 .ico 只给一个画面（它挑最大的那个），所以每个尺寸另写一份单独复核
        java.io.File(dir, "only16.ico").writeBytes(Ico.write(listOf(IcoImage(16, 16, expected(16)))))
        java.io.File(dir, "only32.ico").writeBytes(Ico.write(listOf(IcoImage(32, 32, expected(32)))))
        assertTrue(java.io.File(dir, "written.ico").length() > 0)
    }
}
