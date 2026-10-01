package com.fileforge.core

import com.fileforge.core.archive.ZipItem
import com.fileforge.core.archive.ZipReader
import com.fileforge.core.archive.ZipWriter
import com.fileforge.core.data.Csv
import com.fileforge.core.data.Xml
import com.fileforge.core.data.Yaml
import com.fileforge.core.doc.DocParagraph
import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonRender
import com.fileforge.core.meta.ImageMeta
import com.fileforge.core.meta.Tiff
import com.fileforge.core.office.DocxRead
import com.fileforge.core.office.Xlsx
import com.fileforge.core.text.SubtitleFormat
import com.fileforge.core.text.Subtitles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 回归测试：一批修过的 bug，各自钉住**修好之后该有的样子**。
 *
 * 输入全是手搭的最小样本（内存字符串、手写 XML、逐字节打包的 TIFF），不依赖夹具文件 ——
 * 这批 bug 的共性是"结构稍微怪一点就整次转换崩掉，或者悄悄丢内容"，
 * 最小样本能把出事的那个角落单独摆出来，修回去的时候也知道钉的是哪一条。
 */
class FixesDataOfficeTest {

    // ---- CSV -----------------------------------------------------------------

    @Test
    fun `CSV 中间的空行是数据_只有收尾的换行不算一条`() {
        // RFC 4180 里中间的空记录就是一条全空字段的数据，删了后面全错位；
        // 文本习惯以换行收尾，所以只有**最后**那一条空记录不是数据
        assertEquals(
            listOf(listOf("a", "b"), listOf(""), listOf("c", "d")),
            Csv.parse("a,b\n\nc,d\n").records,
            "中间的空行是数据，不许被当成收尾换行删掉",
        )
        assertEquals(2, Csv.parse("a,b\nc,d\n").records.size, "结尾的换行不该多出一条空记录")
    }

    // ---- YAML ----------------------------------------------------------------

    private fun yaml(text: String): String = JsonRender.render(Yaml.parse(text))

    @Test
    fun `YAML 空的序列项不吃掉后面的兄弟项`() {
        // 旧的读法把空项后面的兄弟当成了它的嵌套值，整个列表只剩一项
        assertEquals("[null,\"第二条\"]", yaml("-\n- 第二条\n"))
    }

    @Test
    fun `YAML 序列项里的行内嵌套序列不当成一项文字`() {
        // "- - 1" 是"一项，值是 [1]"，不是字符串"- 1"
        assertEquals("[[1],\"甲\"]", yaml("- - 1\n- 甲\n"))
    }

    @Test
    fun `YAML 紧凑写法的键下序列照读`() {
        assertEquals("""{"key":["甲"]}""", yaml("key:\n- 甲\n"))
    }

    // ---- 字幕 ------------------------------------------------------------------

    @Test
    fun `LRC 正 offset 让歌词提前而不是推后`() {
        val cues = Subtitles.parse(SubtitleFormat.Lrc, "[offset:+500]\n[00:10.00]一句\n")
        assertEquals(9_500L, cues.single().startMs, "LRC 的约定是正 offset 整体提前：10000 - 500")
    }

    @Test
    fun `LRC 夹在歌词中间的 offset 也对整份生效`() {
        // offset 标签不总在文件头：有些制作工具把它写在歌词后面，只对之前的行生效等于没修
        val cues = Subtitles.parse(SubtitleFormat.Lrc, "[00:10.00]一句\n[offset:+500]\n[00:20.00]二句\n")
        assertEquals(listOf(9_500L, 19_500L), cues.map { it.startMs }, "写在后面的 offset 也要管到前面的行")
        assertEquals(listOf("一句", "二句"), cues.map { it.text })
    }

    @Test
    fun `ASS 的结尾是百分秒不是毫秒`() {
        val ass = "[Script Info]\nScriptType: v4.00+\n\n[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:20.5,0:00:24.50,Default,,0,0,0,,一句\n"
        val cues = Subtitles.parse(SubtitleFormat.Ass, ass)
        assertEquals(20_050L, cues.single().startMs, ".5 是 5 个百分秒 = 50ms，不是半秒")
        assertEquals(24_500L, cues.single().endMs, ".50 是 50 个百分秒 = 500ms")
    }

    // ---- TIFF ------------------------------------------------------------------

    private fun le16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())

    private fun le32(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(), ((value ushr 24) and 0xFF).toByte(),
    )

    /** 一条 12 字节的 IFD 条目：tag(2) type(2) count(4) 值/偏移(4)。 */
    private fun ifdEntry(tag: Int, type: Int, count: Long, value: Long): ByteArray =
        le16(tag) + le16(type) + le32(count) + le32(value)

    /** 小端 TIFF 头：II + 42 + 第一张 IFD 在偏移 8。 */
    private val tiffHead = "II".toByteArray(Charsets.ISO_8859_1) + le16(42) + le32(8)

    @Test
    fun `TIFF 认不出的类型与离谱的个数只跳那一条_不掀翻整个 IFD`() {
        // 头 8 字节 + IFD（条数 2 + 三条 36 + 下一张偏移 4 = 42）= 50，"TestCam\0" 从 50 起
        val bytes = tiffHead +
            le16(3) +
            ifdEntry(0x010E, 20, 1, 0) +            // 规范里没有 type 20：跳过这一条，别把整张 IFD 判死
            ifdEntry(0x0110, 2, 0x8000_0000L, 46) + // 个数 21 亿早冲出文件：跳过，不能崩
            ifdEntry(0x010F, 2, 8, 50) +            // 制造商，8 字节装不进值字段，走偏移
            le32(0) +
            "TestCam\u0000".toByteArray(Charsets.ISO_8859_1)
        assertEquals(listOf("制造商" to "TestCam"), Tiff.read(bytes), "两条坏条目跳过，好的那条要读出来")
    }

    @Test
    fun `TIFF 有理数按有符号读_负值不能变成四十亿`() {
        // SRATIONAL（type 10）一个值 8 字节，值区只有 4 字节，走偏移：-1/3，小端 FFFFFFFF 00000003
        val bytes = tiffHead +
            le16(1) +
            ifdEntry(0x9201, 10, 1, 26) +
            le32(0) +
            le32(0xFFFF_FFFFL) + le32(3)
        val read = Tiff.read(bytes)
        assertEquals("拍摄参数 快门速度（APEX）" to "-0.3333", read.single(), "i32 要补符号：$read")
    }

    // ---- 图片签名 ---------------------------------------------------------------

    @Test
    fun `PNG 签名的第一个字节也在签名里`() {
        // 0x89 'P' 'N' 'G' \r \n \x1a \n 才是完整签名；只看 "PNG" 三个字，
        // 任何这三个字开头的文件都会被当成 PNG 拆
        val png = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        ) + ByteArray(8)
        assertEquals(ImageMeta.Container.Png, ImageMeta.container(png))
        val fake = byteArrayOf(
            'x'.code.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        ) + ByteArray(8)
        assertNull(ImageMeta.container(fake), "首字节不是 0x89 就不是 PNG")
    }

    // ---- XLSX ------------------------------------------------------------------

    private val sheetNs = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""

    private fun sheetXml(body: String) = ("<worksheet $sheetNs><sheetData>$body</sheetData></worksheet>").toByteArray()

    @Test
    fun `XLSX 没有列字母的坏引用丢掉并说明_不是崩`() {
        // r="12" 里没有列字母，列号给不出来：旧代码拿 values[-1] 把整次转换掀翻
        val sheet = Xlsx.sheet(
            "测试",
            sheetXml("<row r=\"1\"><c r=\"12\" t=\"n\"><v>5</v></c></row>"),
            emptyList(),
            emptySet(),
        )
        assertTrue(sheet.notes.any { "引用写得不完整" in it }, "要说清丢了格子：${sheet.notes}")
        assertEquals(emptyList<List<String>>(), sheet.rows, "唯一的格子丢掉后整行是空的，不留行")
    }

    @Test
    fun `XLSX 共享文字里的注音不算格子内容`() {
        // 日文 Excel 把读音（rPh）写在正文前面：不过滤的话格子值就成了"ヨミ漢字"
        val sst = ("<sst $sheetNs><si><rPh><t>ヨミ</t></rPh><r><t>漢字</t></r></si></sst>").toByteArray()
        assertEquals(listOf("漢字"), Xlsx.sharedStrings(sst))
        val sheet = Xlsx.sheet(
            "注音",
            sheetXml("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>"),
            Xlsx.sharedStrings(sst),
            emptySet(),
        )
        assertEquals(listOf(listOf("漢字")), sheet.rows)
    }

    // ---- DOCX ------------------------------------------------------------------

    /** 手搭一份最小的 docx（DocxReadTest 同款）：sdt 与 fldSimple 用写侧产不出来，只能照 ECMA-376 拼。 */
    private fun docxOf(body: String): ByteArray {
        val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        val head = "<w:document $w xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>"
        val items = listOf(
            part("word/document.xml", head + body + "</w:body></w:document>"),
            part("word/styles.xml", "<w:styles $w/>"),
            part("word/numbering.xml", "<w:numbering $w/>"),
        )
        return ZipWriter.write(items)
    }

    private fun part(name: String, xml: String) = ZipItem(
        name,
        ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" + xml).toByteArray(Charsets.UTF_8),
        0L,
    )

    /** 把包摊成"部件名 → 字节"，交给读的一侧按需取。 */
    private fun loader(bytes: ByteArray): (String) -> ByteArray? {
        val archive = ZipReader.read(bytes)
        val slices = ZipReader.slicing(bytes)
        val index = archive.entries.associate { entry -> entry.name to ZipReader.dataOf(entry, slices) }
        return { name -> index[name] }
    }

    @Test
    fun `DOCX 内容控件与简单域里的字照读`() {
        // 模板与封面爱把正文裹进 sdt，日期域的结果躺在 fldSimple 里：
        // 旧读法这两处的字整段消失，用户拿到的是缺内容的产物
        val bytes = docxOf(
            body = "<w:sdt><w:sdtPr/><w:sdtContent>" +
                "<w:p><w:r><w:t>内容控件里的字</w:t></w:r></w:p>" +
                "</w:sdtContent></w:sdt>" +
                "<w:p><w:fldSimple w:instr=\"DATE\"><w:r><w:t>2026</w:t></w:r></w:fldSimple></w:p>",
        )
        val read = DocxRead.read(loader(bytes))
        val texts = read.doc.parts.filterIsInstance<DocParagraph>()
            .map { it.para.runs.joinToString("") { run -> run.text } }
        assertEquals(listOf("内容控件里的字", "2026"), texts, "sdt 与 fldSimple 里的字不许丢：$texts")
    }

    // ---- XML -------------------------------------------------------------------

    @Test
    fun `XML 声明的编码不是 UTF-8 也不重编一遍`() {
        // 交给解析器的得是字符流：text 已经是解对了的 String，再按字节交回去
        // 会用平台默认字符集重编一遍，声明里写着 ISO-8859-1 的文档就被读成乱码
        val parsed = Xml.parse("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><r><a>café</a></r>")
        assertEquals("café", parsed.field("r")!!.field("a")!!.arrayValue.single().stringValue)
    }

    @Test
    fun `XML 渲染时挂在 #text 上的数字不许丢`() {
        // #text 不一定是字符串（数字、布尔也会挂在这个键上）：一律取成文字，
        // 不然这个值既不进正文也不进孩子，转完就静悄悄没了
        val xml = Xml.render(Json.parse("""{"a": {"#text": 123}}"""))
        assertTrue("<a>123</a>" in xml, "数字正文要照写法写出来：$xml")
    }
}
