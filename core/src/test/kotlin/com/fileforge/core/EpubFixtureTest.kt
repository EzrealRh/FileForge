package com.fileforge.core

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.book.Epub
import com.fileforge.core.book.EpubBook
import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.Html
import com.fileforge.core.json.Json
import com.fileforge.core.office.DocxWrite
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 拿真 EPUB 走一遍整条路：zip → spine 排章 → 三种出路，产物落到 `build/epub/`。
 *
 * 期望值**不写在这里**，读 `tools/make_epub_fixtures.py` 生成的 `manifest.json`：
 * 生成、判断、判据三处只有一个人说得上话。外部对照（pandoc、ElementTree）在
 * `tools/verify_epub.py`，这里只管自家那侧算出来的东西别自相矛盾。
 */
class EpubFixtureTest {

    private fun resource(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("epub/$name").use { input ->
            requireNotNull(input) { "缺少夹具 epub/$name，先跑 python tools/make_epub_fixtures.py" }
            ByteArrayOutputStream().also { output -> input.copyTo(output) }.toByteArray()
        }

    private fun open(bytes: ByteArray): EpubBook {
        val archive = ZipReader.read(bytes)
        val slices = ZipReader.slicing(bytes)
        return Epub.read(archive.entries.map { it.name }) { name ->
            val entry = archive.entries.firstOrNull { it.name == name } ?: return@read null
            ZipReader.dataOf(entry, slices)
        }
    }

    private fun plain(book: EpubBook): String =
        book.chapters.joinToString("\n\n") { Html.toPlainText(it.source).text }

    private fun markdown(book: EpubBook): String =
        book.chapters.joinToString("\n\n") { Html.toMarkdown(it.source).text }

    private fun docx(book: EpubBook): ByteArray {
        val parts = ArrayList<DocPart>()
        book.chapters.forEach { parts += Html.toDoc(it.source).parts }
        return DocxWrite.document(Doc(parts, book.notes), modifiedAt = 0L).bytes
    }

    @Test
    fun `书的章序与章名按 manifest 声明`() {
        val manifest = Json.parse(String(resource("manifest.json"), Charsets.UTF_8))
        val declared = manifest.members.getValue("chapters").arrayValue
        val book = open(resource("book.epub"))
        assertEquals(declared.size, book.chapters.size)
        assertEquals(
            declared.map { it.members.getValue("path").stringValue },
            book.chapters.map { it.part },
        )
        assertEquals(
            declared.map { it.members.getValue("title").stringValue },
            book.chapters.map { it.title },
        )
        assertEquals(
            manifest.members.getValue("title").stringValue, book.title,
        )
        assertEquals(
            manifest.members.getValue("author").stringValue, book.author,
        )
    }

    @Test
    fun `没排进 spine 的部件与图片都不进正文`() {
        val manifest = Json.parse(String(resource("manifest.json"), Charsets.UTF_8))
        val book = open(resource("book.epub"))
        val unused = manifest.members.getValue("not_in_spine").arrayValue.map { it.stringValue }
        assertFalse(book.chapters.any { it.part in unused }, "没排进顺序的部件混进正文了")
        assertEquals(1, book.images)
        assertTrue(book.notes.any { it.contains("没被 spine 用到") }, book.notes.toString())
        // 线性为 no 的那次重复不能出现第三遍
        assertEquals(1, book.chapters.count { it.part.endsWith("ch2.xhtml") })
    }

    @Test
    fun `utf16 那本书的字不是乱码`() {
        val manifest = Json.parse(String(resource("manifest.json"), Charsets.UTF_8))
        val want = manifest.members.getValue("utf16").members.getValue("texts").arrayValue
            .map { it.stringValue.orEmpty() }
        val book = open(resource("utf16.epub"))
        assertEquals(1, book.chapters.size)
        val text = plain(book)
        want.forEach { assertTrue(text.contains(it), "读不到「$it」：$text") }
    }

    @Test
    fun `产物落到 build 供外部判据对照`() {
        val book = open(resource("book.epub"))
        val dir = File("build/epub").apply { mkdirs() }
        File(dir, "order.txt").writeText(
            book.chapters.joinToString("\n") { "${it.part}\t${it.title}" } + "\n", Charsets.UTF_8,
        )
        File(dir, "notes.txt").writeText(book.notes.joinToString("\n") + "\n", Charsets.UTF_8)
        File(dir, "book.txt").writeText(plain(book), Charsets.UTF_8)
        File(dir, "book.md").writeText(markdown(book), Charsets.UTF_8)
        File(dir, "notes-md.txt").writeText(
            book.chapters.flatMap { Html.toMarkdown(it.source).notes }.joinToString("\n") + "\n",
            Charsets.UTF_8,
        )
        File(dir, "structure.docx").writeBytes(docx(book))
        book.chapters.forEachIndexed { index, chapter ->
            File(dir, "chapter-${index + 1}.txt").writeText(Html.toPlainText(chapter.source).text, Charsets.UTF_8)
        }
        val u16 = open(resource("utf16.epub"))
        File(dir, "utf16.txt").writeText(plain(u16), Charsets.UTF_8)
        File(dir, "utf16.md").writeText(markdown(u16), Charsets.UTF_8)
        assertEquals(4, book.chapters.size)
    }
}
