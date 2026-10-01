package com.fileforge.core

import com.fileforge.core.book.Epub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * EPUB 的拆件：谁在前、路径怎么对、名字从哪儿来、缺件时说什么。
 *
 * 判据集中在**顺序**与**对位**这两件最容易悄悄错的事上 ——
 * 章节顺序错了，读者看到的是"内容都在但读不通"，最难发现。
 */
class EpubTest {

    private fun page(title: String, body: String = "<p>$title 的正文。</p>") =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml">""" +
            "<head><title>$title</title></head><body>$body</body></html>"

    private fun opf(items: String, spine: String, meta: String = "<dc:title>书名叫这个</dc:title>") =
        """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" """ +
            """unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$meta</metadata>""" +
            "<manifest>$items</manifest><spine>$spine</spine></package>"

    private fun container(path: String = "OEBPS/content.opf") =
        """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" """ +
            """xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>""" +
            """<rootfile full-path="$path" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private fun book(opf: String, vararg extra: Pair<String, ByteArray>) =
        mutableMapOf<String, ByteArray>(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container().toByteArray(Charsets.UTF_8),
            "OEBPS/content.opf" to opf.toByteArray(Charsets.UTF_8),
        ).apply { extra.forEach { put(it.first, it.second) } }

    private fun utf8(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `章节按 spine 的顺序，不按文件名`() {
        val opf = opf(
            """<item id="b" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>""" +
                """<item id="a" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/><itemref idref="b"/>""",
        )
        val read = Epub.read(book(opf, "OEBPS/text/ch1.xhtml" to utf8(page("第一章")),
            "OEBPS/text/ch2.xhtml" to utf8(page("第二章"))))
        assertEquals(listOf("第二章", "第一章"), read.chapters.map { it.title })
        assertEquals("书名叫这个", read.title)
    }

    @Test
    fun `href 是相对 OPF 的，带片段与百分号也要对得上`() {
        val opf = opf(
            """<item id="a" href="text/first%20part.xhtml#intro" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/>""",
        )
        val read = Epub.read(book(opf, "OEBPS/text/first part.xhtml" to utf8(page("空格在名字里"))))
        assertEquals(1, read.chapters.size)
        assertEquals("空格在名字里", read.chapters.first().title)
    }

    @Test
    fun `rootfile 写成绝对路径或大小写不同照样找到`() {
        val opf = opf(
            """<item id="a" href="../OEBPS/text/ch.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/>""",
        )
        val entries = mapOf(
            "META-INF/container.xml" to container("/OEBPS/content.opf").toByteArray(Charsets.UTF_8),
            "OEBPS/content.opf" to opf.toByteArray(Charsets.UTF_8),
            "OEBPS/text/ch.xhtml" to utf8(page("往上跳一层")),
        )
        assertEquals("往上跳一层", Epub.read(entries).chapters.single().title)
    }

    @Test
    fun `NCX 的章名优先于文档里的 title`() {
        val ncx = """<?xml version="1.0" encoding="UTF-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">""" +
            """<navMap><navPoint id="n1"><navLabel><text>第三节 按目录叫的这个名</text></navLabel>""" +
            """<content src="text/ch3.xhtml"/></navPoint></navMap></ncx>"""
        val opf = opf(
            """<item id="t" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""" +
                """<item id="a" href="text/ch3.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/>""",
        )
        val read = Epub.read(book(opf, "OEBPS/toc.ncx" to utf8(ncx), "OEBPS/text/ch3.xhtml" to utf8(page("第三章"))))
        assertEquals("第三节 按目录叫的这个名", read.chapters.single().title)
    }

    @Test
    fun `非线性的项不进正文，样式图片只数不算正文`() {
        val opf = opf(
            """<item id="a" href="text/ch.xhtml" media-type="application/xhtml+xml"/>""" +
                """<item id="cover" href="img/cover.png" media-type="image/png"/>""" +
                """<item id="css" href="style/book.css" media-type="text/css"/>""",
            """<itemref idref="a"/><itemref idref="a" linear="no"/>""",
        )
        val read = Epub.read(book(opf, "OEBPS/text/ch.xhtml" to utf8(page("一章")),
            "OEBPS/img/cover.png" to byteArrayOf(1, 2, 3)))
        assertEquals(1, read.chapters.size)
        assertEquals(1, read.images)
        assertTrue(read.notes.any { it.contains("图片") }, read.notes.toString())
    }

    @Test
    fun `缺件与对不上的 id 只报数，不崩`() {
        val opf = opf(
            """<item id="a" href="text/gone.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/><itemref idref="nope"/>""",
        )
        val read = Epub.read(book(opf))
        assertTrue(read.chapters.isEmpty())
        assertTrue(read.notes.any { it.contains("不在包里") }, read.notes.toString())
        assertTrue(read.notes.any { it.contains("清单里没有") }, read.notes.toString())
    }

    @Test
    fun `UTF-16 的章节读得出来`() {
        val opf = opf(
            """<item id="a" href="text/utf16.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/>""",
        )
        val page = page("十六位编码").toByteArray(Charsets.UTF_16LE)
        val read = Epub.read(book(opf, "OEBPS/text/utf16.xhtml" to page))
        assertEquals("十六位编码", read.chapters.single().title)
    }

    @Test
    fun `没有 container 的 zip 直说不是 EPUB`() {
        val bad = mapOf("mimetype" to "application/epub+zip".toByteArray())
        val error = assertThrows(IllegalArgumentException::class.java) { Epub.read(bad) }
        assertTrue(error.message!!.contains("不是 EPUB"), error.message)
    }

    @Test
    fun `带 DRM 的包说的是读不出来而不是这不是电子书`() {
        // 加过密的 EPUB 把整套目录挪进 encrypted/ 那一层，容器文件本身也是密文
        val drm = mapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "encrypted/META-INF/container.xml" to "密文".toByteArray(),
        )
        val error = assertThrows(IllegalArgumentException::class.java) { Epub.read(drm) }
        assertTrue(error.message!!.contains("DRM"), error.message)
    }

    @Test
    fun `没有作者时是空而不是编一个`() {
        val opf = opf(
            """<item id="a" href="text/ch.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/>""",
            "<dc:title>只有书名</dc:title>",
        )
        val read = Epub.read(book(opf, "OEBPS/text/ch.xhtml" to utf8(page("一章"))))
        assertNull(read.author)
        assertEquals("只有书名", read.title)
    }

    @Test
    fun `EPUB3 只有 nav 目录时也拿它当章名，且不把目录读成一章`() {
        val opf = opf(
            """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" +
                """<item id="a" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>""" +
                """<item id="b" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/><itemref idref="b"/>""",
        )
        val nav = """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE html>""" +
            """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""" +
            """<body><nav epub:type="toc" id="toc"><ol>""" +
            """<li><a href="text/ch1.xhtml#甲">目录里的第一章</a></li>""" +
            """<li><a href="text/ch2.xhtml">目录里的第二章</a></li></ol></nav>""" +
            """<nav epub:type="landmarks" id="landmarks"><ol>""" +
            """<li><a href="text/ch2.xhtml" epub:type="start">正文起始</a></li></ol></nav></body></html>"""
        val read = Epub.read(
            book(
                opf,
                "OEBPS/nav.xhtml" to utf8(nav),
                // 章节文件自己的 title 故意和目录不一样：目录给的应当赢
                "OEBPS/text/ch1.xhtml" to utf8(page("文件里的标题甲")),
                "OEBPS/text/ch2.xhtml" to utf8(page("文件里的标题乙")),
            ),
        )
        assertEquals(listOf("目录里的第一章", "目录里的第二章"), read.chapters.map { it.title })
        assertEquals(listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"), read.chapters.map { it.part })
        assertTrue(read.notes.none { it.contains("没被 spine 用到") }, read.notes.toString())
    }

    @Test
    fun `NCX 与 nav 都在时名字听 NCX 的`() {
        val opf = opf(
            """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""" +
                """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" +
                """<item id="a" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/>""",
        )
        val ncx = """<?xml version="1.0" encoding="UTF-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">""" +
            """<docTitle><text>这本书</text></docTitle><navMap>""" +
            """<navPoint id="n1" playOrder="1"><navLabel><text>NCX 给的名字</text></navLabel>""" +
            """<content src="text/ch1.xhtml"/></navPoint></navMap></ncx>"""
        val nav = """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE html>""" +
            """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""" +
            """<body><nav epub:type="toc" id="toc"><ol><li><a href="text/ch1.xhtml">nav 给的名字</a></li></ol></nav></body></html>"""
        val read = Epub.read(
            book(opf, "OEBPS/toc.ncx" to utf8(ncx), "OEBPS/nav.xhtml" to utf8(nav),
                "OEBPS/text/ch1.xhtml" to utf8(page("文件里的标题"))),
        )
        assertEquals(listOf("NCX 给的名字"), read.chapters.map { it.title })
    }

    @Test
    fun `GBK 编码的章节按声明解出来而不是按 UTF-8 硬解`() {
        // 中文电子书常见：XHTML 用 GB18030 写，XML 声明里写着 encoding="gb18030"
        val chapter = ("""<?xml version="1.0" encoding="gb18030"?><html xmlns="http://www.w3.org/1999/xhtml">""" +
            "<head><title>第一章</title></head><body><p>你好，转出来该是这些字。</p></body></html>")
            .toByteArray(charset("GB18030"))
        val opf = opf(
            """<item id="a" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/>""",
        )
        val read = Epub.read(book(opf, "OEBPS/text/ch1.xhtml" to chapter))
        assertEquals("第一章", read.chapters.single().title, "标题不能是乱码")
        assertTrue(read.chapters.single().source.contains("你好"), read.chapters.single().source.take(80))
    }

    @Test
    fun `HTML 章节用 meta charset 声明编码也认`() {
        val chapter = ("<html><head><meta charset=\"gbk\"><title>第二章</title></head>" +
            "<body><p>简体中文内容测试</p></body></html>").toByteArray(charset("GBK"))
        val opf = opf(
            """<item id="a" href="text/ch1.html" media-type="text/html"/>""",
            """<itemref idref="a"/>""",
        )
        val read = Epub.read(book(opf, "OEBPS/text/ch1.html" to chapter))
        assertTrue(read.chapters.single().source.contains("简体中文内容测试"), read.chapters.single().source.take(80))
    }

    @Test
    fun `声明写着 gb2312 但字节其实是 UTF-8 时按字节走`() {
        // 野文件常见：声明瞎写。UTF-8 按 GBK 解出来"很干净"，零替换符的闸门拦不住这个方向，
        // 所以字节本身是合法且带多字节的 UTF-8 时，直接按 UTF-8 走并改写声明
        val chapter = ("""<?xml version="1.0" encoding="gb2312"?><html xmlns="http://www.w3.org/1999/xhtml">""" +
            "<head><title>第一章</title></head><body><p>你好，其实我是 UTF-8 的字节。</p></body></html>")
            .toByteArray(Charsets.UTF_8)
        val opf = opf(
            """<item id="a" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/>""",
        )
        val read = Epub.read(book(opf, "OEBPS/text/ch1.xhtml" to chapter))
        assertEquals("第一章", read.chapters.single().title, "标题不能是乱码")
        assertTrue(read.chapters.single().source.contains("其实我是 UTF-8"), read.chapters.single().source.take(80))
    }

    @Test
    fun `UTF-8 章节混进少量坏字节时按 UTF-8 修补而不是整份按声明硬解`() {
        // 把 0xFF 塞进"你好，"和"这一段"之间：严格 UTF-8 失败，但替换符只有 1 个 ——
        // 是"混了刺的 UTF-8"，不是"整份 GBK 被硬解"（那种替换符是十几万级别）
        val head = """<?xml version="1.0" encoding="gb2312"?><html xmlns="http://www.w3.org/1999/xhtml">""" +
            "<head><title>第一章</title></head><body><p>你好，"
        val tail = "这一段后面混进了一个坏字节，其余要完好。</p></body></html>"
        val bytes = head.toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte()) + tail.toByteArray(Charsets.UTF_8)
        val opf = opf(
            """<item id="a" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/>""",
        )
        val read = Epub.read(book(opf, "OEBPS/text/ch1.xhtml" to bytes))
        assertEquals("第一章", read.chapters.single().title)
        assertTrue(read.chapters.single().source.contains("其余要完好"), read.chapters.single().source.take(120))
    }

    @Test
    fun `没声明编码的 GBK 章节按 GB18030 兜底`() {
        val chapter = "<html><head><title>第一章</title></head><body><p>繁杂的中文内容测试通过</p></body></html>"
            .toByteArray(charset("GB18030"))
        val opf = opf(
            """<item id="a" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="a"/>""",
        )
        val read = Epub.read(book(opf, "OEBPS/text/ch1.xhtml" to chapter))
        assertTrue(read.chapters.single().source.contains("繁杂的中文内容测试"), read.chapters.single().source.take(80))
    }
}
