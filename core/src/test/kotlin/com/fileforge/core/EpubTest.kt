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
}
