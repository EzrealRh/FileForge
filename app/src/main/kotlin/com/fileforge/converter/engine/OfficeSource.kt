package com.fileforge.converter.engine

import com.fileforge.core.archive.ZipReader
import com.fileforge.converter.data.FileSlices
import com.fileforge.core.doc.HtmlWrite
import com.fileforge.core.model.FileKind
import com.fileforge.core.office.DocxBody
import com.fileforge.core.office.DocxRead
import com.fileforge.core.office.Extracted
import com.fileforge.core.office.PptxRead
import com.fileforge.core.office.PptxSlideBody
import com.fileforge.core.office.OdtBody
import com.fileforge.core.office.OdtRead
import com.fileforge.core.office.OfficeText
import com.fileforge.core.office.OoxmlParts
import com.fileforge.core.office.OoxmlStructure
import com.fileforge.core.office.SheetRef
import com.fileforge.core.office.Xlsx
import java.io.Closeable
import java.io.File

/**
 * 一份打开的 office zip 包：目录先读，正文按部件名取。
 *
 * 名字里的 OOXML 是历史：ODF（.odt）也是"zip + 固定部件名"，取字节这一模一样，
 * 于是并在这里只留一份区间读的写法（两种格式差的是**读哪个部件、怎么解**，那在 core 那边）。
 *
 * 只走区间读，不整包进堆 —— 带视频的 pptx 可以有几百 MB，而正文部件通常只有几百 KB。
 */
class OoxmlFile(val file: File) : Closeable {
    private val slices = FileSlices(file)
    private val archive = ZipReader.read(slices, file.length())
    val names: List<String> = archive.entries.map { it.name }

    /** ODF 那一族光看条目名分不出是文字还是表格，要把 `mimetype` 那条读出来才知道。 */
    val kind: FileKind = OoxmlParts.kindOf(names) { name -> bytesOf(name) }

    /** 某个部件解压后的字节；没有这个部件就是 null。 */
    fun bytesOf(part: String): ByteArray? {
        val entry = archive.entries.firstOrNull { it.name == part } ?: return null
        require(!entry.isEncrypted) { "这份包带口令，先解掉密码再来" }
        require(entry.size <= MAX_PART_BYTES) {
            "部件 $part 有 ${entry.size / 1024 / 1024} MB，超过 ${MAX_PART_BYTES / 1024 / 1024} MB 上限"
        }
        return ZipReader.dataOf(entry, slices)
    }

    /**
     * docx 的**结构**（标题层级、列表、表格、记号、真链接）读成文档树。
     *
     * 与抽文字那条不是一条路：那条只把字连起来，样式与编号这些信息它用不上；
     * 转 Markdown / 转网页要靠这些才能把稿子的层级搬过去。
     */
    fun docxStructure(): DocxBody = DocxRead.read { name -> bytesOf(name) }

    /**
     * odt 的**结构**读成文档树（见 `:core` 的 OdtRead）。
     *
     * ODF 的记号全在样式表里，正文只写样式名，所以这一条要连 styles.xml 一起取 ——
     * 缺了也不报错，只是认不出母样式那一层的引号（读的时候会照字面搬文字并说明）。
     */
    fun odtStructure(): OdtBody = OdtRead.read { name -> bytesOf(name) }

    /**
     * pptx 的**结构**读成文档树（见 `:core` 的 PptxRead）。
     *
     * 页序按演示大纲（`presentation.xml` 的 sldIdLst）走，与"抽文字"那条同一次判定；
     * 每页的关系表单独取 —— 链接地址挂在页上，不在包级的关系表里。
     */
    fun pptxStructure(): PptxSlideBody = PptxRead.read({ name -> bytesOf(name) }, names)

    /** 幻灯片部件，顺序照演示大纲；大纲认不出来时退回按文件名排，并在说明里写清楚。 */
    fun slideParts(): DeclaredSlides {
        val slides = names.filter { it.startsWith("ppt/slides/") && it.endsWith(".xml") }.sorted()
        val order = OoxmlStructure.slideOrder({ name -> bytesOf(name) }, names)
        if (order.parts.isEmpty()) return DeclaredSlides(slides, order.notes + "认不出演示大纲，幻灯片改成按文件名排了")
        return DeclaredSlides(order.parts, order.notes)
    }

    fun sheetRefs(): List<SheetRef> = Xlsx.sheets { name -> bytesOf(name) }

    class DeclaredSlides(val parts: List<String>, val notes: List<String>)

    override fun close() {
        runCatching { slices.close() }
    }

    private companion object {
        /** 单个部件的上限：正文与样式都是 KB 级，超了就是文件坏了或者拿图当字存了。 */
        const val MAX_PART_BYTES = 32L * 1024 * 1024
    }
}

/**
 * docx / pptx 抽正文。引擎与"印成 PDF"都要用，所以放在这里只留一份。
 */
object OfficeSource {

    /** 一份文档的全部正文文字，外加"这份文件里有什么没搬过来"。 */
    fun text(item: File, kind: FileKind): Extracted = OoxmlFile(item).use { pack ->
        when (kind) {
            FileKind.Docx -> docx(pack)
            FileKind.Pptx -> pptx(pack)
            FileKind.Odt -> odt(pack)
            FileKind.Xlsx -> throw IllegalArgumentException("表格请走「表格转 CSV」，它不是连着读的正文")
            else -> throw IllegalArgumentException("这份包里没找到 Word / 演示文稿 / ODT 的正文部件")
        }
    }

    /**
     * odt 抽正文：走的是**结构**那棵树，不是连字。
     *
     * 段落边界在 ODF 里是写在文件里的（`text:p`），连字会把它抹平成一片换行；
     * 走树还与转 Markdown / 网页 / Word 四条路同一份读，四条路只会差在怎么落笔。
     */
    private fun odt(pack: OoxmlFile): Extracted {
        val read = pack.odtStructure()
        return Extracted(HtmlWrite.text(read.doc.parts), read.notes)
    }

    private fun docx(pack: OoxmlFile): Extracted {
        val body = pack.bytesOf(OoxmlParts.DOCX_BODY)
            ?: throw IllegalArgumentException("这份包里找不到 word/document.xml，它不是 docx")
        val extracted = OfficeText.docx(body)
        val notes = ArrayList(extracted.losses)
        if (pack.names.any { it.startsWith("word/embeddings/") }) notes += "文件里嵌着的别的文档只是附件，没当成文字搬"
        return Extracted(extracted.text, notes)
    }

    private fun pptx(pack: OoxmlFile): Extracted {
        val declared = pack.slideParts()
        val parts = declared.parts
        require(parts.isNotEmpty()) { "这份包里一页幻灯片都没有，它不是 pptx" }
        val out = StringBuilder()
        val losses = ArrayList(declared.notes)
        parts.forEachIndexed { index, part ->
            val page = pack.bytesOf(part) ?: return@forEachIndexed
            val extracted = OfficeText.pptxSlide(page)
            out.append("第 ").append(index + 1).append(" 页\n").append(extracted.text)
            losses += extracted.losses
        }
        if (pack.names.any { it.startsWith("ppt/notesSlides/") }) losses += "演讲者备注在别的部件里，没搬"
        if (pack.names.any { it.startsWith("ppt/slideMasters/") || it.startsWith("ppt/slideLayouts/") }) {
            losses += "母版与版式里的固定文字（页眉、logo 上的字）不搬"
        }
        return Extracted(out.toString(), losses.distinct())
    }
}
