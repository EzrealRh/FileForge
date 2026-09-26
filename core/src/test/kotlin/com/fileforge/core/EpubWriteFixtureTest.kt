package com.fileforge.core

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.book.Epub
import com.fileforge.core.book.EpubBook
import com.fileforge.core.book.EpubWrite
import com.fileforge.core.doc.TextDoc
import com.fileforge.core.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 拿五份真来料走一遍写的一侧：认路 → 切章 → 打包，产物落到 `build/epubwrite/`。
 *
 * 期望值不写在这里，读 `tools/make_epub_write_fixtures.py` 生成的 `manifest.json` ——
 * 那份 manifest 里的章名、语言、书号是 Python 另一套实现算出来的，两边各算各的。
 * 外部的第三、第四方（pandoc 读回来、ElementTree 判良构）在 `tools/verify_epub_write.py`。
 */
class EpubWriteFixtureTest {

    private val stamp = 1_700_000_000_000L

    /** manifest 里一条案例：来料名 + 该产出什么。 */
    private class Case(json: Json) {
        val source = json.members.getValue("source").stringValue.orEmpty()
        val title = json.members.getValue("title").stringValue.orEmpty()
        val author = json.members.getValue("author").stringValue.orEmpty()
        val language = json.members.getValue("language").stringValue.orEmpty()
        val identifier = json.members.getValue("identifier").stringValue.orEmpty()
        val chapters = json.members.getValue("chapters").arrayValue.map { it.stringValue.orEmpty() }
        val stem = source.substringBeforeLast('.')
    }

    private fun resource(name: String): String {
        val input = javaClass.classLoader.getResourceAsStream("epubwrite/$name")
        requireNotNull(input) { "缺少夹具 epubwrite/$name，先跑 python tools/make_epub_write_fixtures.py" }
        val bytes = ByteArrayOutputStream().also { output -> input.copyTo(output) }.toByteArray()
        return String(bytes, Charsets.UTF_8)
    }

    private fun cases(): List<Case> =
        Json.parse(resource("manifest.json")).members.getValue("cases").arrayValue.map { Case(it) }

    /** 与 App 那条路同一条式子：认路 → 按一级标题切章 → 打包。 */
    private fun build(source: String, stem: String, title: String, author: String): ByteArray {
        val doc = TextDoc.read(source).doc
        return EpubWrite.book(
            title = title,
            author = author.ifBlank { null },
            pages = EpubWrite.chaptersOf(doc, stem),
            language = EpubWrite.languageOf(doc),
            identifier = EpubWrite.identifierFor(title + source),
            modifiedAt = stamp,
        ).bytes
    }

    private fun build(case: Case): ByteArray = build(resource(case.source), case.stem, case.title, case.author)

    private fun part(bytes: ByteArray, name: String): String {
        val archive = ZipReader.read(bytes)
        val entry = archive.entries.firstOrNull { it.name == name }
        requireNotNull(entry) { "包里没有 $name" }
        return String(ZipReader.dataOf(entry, ZipReader.slicing(bytes)), Charsets.UTF_8)
    }

    /** 整包都在手上，直接摊成"名字 → 字节"给读的一侧。 */
    private fun openBook(bytes: ByteArray): EpubBook {
        val archive = ZipReader.read(bytes)
        val slices = ZipReader.slicing(bytes)
        return Epub.read(archive.entries.associate { entry -> entry.name to ZipReader.dataOf(entry, slices) })
    }

    @Test
    fun `章名语言与书号与 manifest 声明的一致`() {
        cases().forEach { case ->
            val source = resource(case.source)
            val doc = TextDoc.read(source).doc
            val bytes = build(case)
            val opf = part(bytes, "OEBPS/content.opf")
            assertEquals(case.chapters, EpubWrite.chaptersOf(doc, case.stem).map { page -> page.title }, "${case.source} 的章名")
            assertEquals("<dc:language>${case.language}</dc:language>", tag(opf, "dc:language"), "${case.source} 的语言")
            assertEquals("<dc:identifier id=\"id\">${case.identifier}</dc:identifier>", tag(opf, "dc:identifier"), "${case.source} 的书号")
            assertEquals("<dc:title>${case.title}</dc:title>", tag(opf, "dc:title"), "${case.source} 的书名")
            assertEquals("<dc:date>2023-11-14T22:13:20Z</dc:date>", tag(opf, "dc:date"), "${case.source} 的日期")
            if (case.author.isBlank()) {
                assertFalse("<dc:creator" in opf, "${case.source} 没有作者却写了一个进去：$opf")
            } else {
                assertEquals("<dc:creator>${case.author}</dc:creator>", tag(opf, "dc:creator"), "${case.source} 的作者")
            }
            // 章文件数与章数对得上，且 spine 的每个 idref 都能落到一个真存在的文件
            val read = openBook(bytes)
            assertEquals(case.chapters, read.chapters.map { it.title }, "${case.source} 读回来的章名")
            assertEquals(case.chapters.size, read.chapters.size)
        }
    }

    private fun tag(opf: String, name: String): String =
        Regex("<$name[^>]*>.*?</$name>", RegexOption.DOT_MATCHES_ALL).find(opf)?.value ?: "<$name 不在>"

    @Test
    fun `同一份内容两次导出字节一模一样`() {
        // 字节稳，产物才能拿去逐字节对照；Map 顺序或时间戳漏进包里就会每次都不一样
        cases().forEach { case ->
            val first = build(case)
            val second = build(case)
            assertTrue(first.contentEquals(second), "${case.source} 两次导出的字节不一样")
        }
    }

    @Test
    fun `产物落到 build 供外部判据对照`() {
        val dir = File("build/epubwrite").apply { mkdirs() }
        cases().forEach { case ->
            val bytes = build(case)
            File(dir, "${case.stem}.epub").writeBytes(bytes)
            val read = openBook(bytes)
            File(dir, "${case.stem}.order.txt").writeText(
                read.chapters.joinToString("\n") { "${it.part}\t${it.title}" } + "\n", Charsets.UTF_8,
            )
            File(dir, "${case.stem}.notes.txt").writeText(read.notes.joinToString("\n") + "\n", Charsets.UTF_8)
            assertEquals(case.chapters.size, read.chapters.size, "${case.source} 写出去读不回来")
        }
    }
}
