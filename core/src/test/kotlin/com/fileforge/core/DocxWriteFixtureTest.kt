package com.fileforge.core

import com.fileforge.core.doc.Html
import com.fileforge.core.doc.Markdown
import com.fileforge.core.doc.PlainDoc
import com.fileforge.core.office.DocxWrite
import com.fileforge.core.office.OfficeText
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 把三条来源（普通文字 / Markdown / 网页）写出的 docx 落到 `build/docx/`，
 * 交给 `tools/verify_docx_write.py` 与 **pandoc**（另一套独立的 docx 读取器）对结构。
 *
 * 这里另外钉一件事：**成品用自家的读路打开，字要一个不少** —— 落盘前后各读一次，
 * 少一个字都会在断言里露出来（真第三方只看结构，掉一两个字它未必报）。
 */
class DocxWriteFixtureTest {

    private fun resource(path: String): String =
        javaClass.classLoader.getResourceAsStream(path).use { input ->
            requireNotNull(input) { "缺少夹具 $path" }
            String(input.readBytes(), Charsets.UTF_8)
        }

    private fun chars(text: String) = text.filter { it in '一'..'鿿' }.toCharArray().toHashSet()

    private fun docxBytes(bytes: ByteArray): ByteArray {
        val slices = com.fileforge.core.archive.ZipReader.slicing(bytes)
        val entries = com.fileforge.core.archive.ZipReader.read(slices, bytes.size.toLong()).entries
        val document = requireNotNull(entries.firstOrNull { it.name == "word/document.xml" }) { "包里没正文部件" }
        return com.fileforge.core.archive.ZipReader.dataOf(document, com.fileforge.core.archive.ZipReader.slicing(bytes))
    }

    /**
     * 落盘并自查一次。
     *
     * 参照文字是**该被读出来的那部分**（纯文本那路已经说清哪些内容会丢），不是源文件 ——
     * 网页的 `<script>`、`<head>` 本来就不该进 Word，拿源文件比字会把这条变成假判据。
     */
    private fun dump(name: String, doc: com.fileforge.core.doc.Doc, expected: String) {
        val out = DocxWrite.document(doc, modifiedAt = 1_700_000_000_000L)
        val dir = File("build/docx").apply { mkdirs() }
        File(dir, "$name.docx").writeBytes(out.bytes)
        File(dir, "$name.notes").writeText(out.notes.joinToString("\n") + "\n", Charsets.UTF_8)
        val read = OfficeText.docx(docxBytes(out.bytes))
        val missing = chars(expected) - chars(read.text)
        assertTrue(missing.isEmpty(), "$name 写进 docx 后自家读回来少了这些字：$missing")
    }

    @Test
    fun `三种来源各写一份 docx`() {
        val plain = resource("docx/plain.txt")
        dump("plain", PlainDoc.toDoc(plain), plain)

        val html = resource("html/clean.html")
        dump("clean", Html.toDoc(html), Html.toPlainText(html).text)

        val markdown = resource("markdown/common.md")
        // Markdown 走自己那套渲染成 HTML，再交给网页那条路建树：两条输出共用一套判定
        dump("common", Html.toDoc(Markdown.toHtml(markdown).text), Markdown.toPlainText(markdown).text)
    }

    @Test
    fun `普通文字按空行分段`() {
        val doc = PlainDoc.toDoc("甲\n乙\n\n丙\n\n\n丁")
        val texts = doc.parts.filterIsInstance<com.fileforge.core.doc.DocParagraph>().map { it.para.text }
        assertEquals(listOf("甲\n乙", "丙", "丁"), texts)
    }
}
