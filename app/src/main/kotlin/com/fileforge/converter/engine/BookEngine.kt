package com.fileforge.converter.engine

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.book.Epub
import com.fileforge.core.book.EpubBook
import com.fileforge.core.book.EpubWrite
import com.fileforge.core.book.Mobi
import com.fileforge.core.doc.Doc
import com.fileforge.core.doc.DocPart
import com.fileforge.core.doc.Html
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.office.DocxWrite
import com.fileforge.core.ops.Operation
import com.fileforge.converter.data.FileSlices
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace

/**
 * EPUB 这一头一尾：读进来有三条出路（抽文字、转 Markdown、写成 Word），写出去是一条（任何文本来料 → 电子书）。
 *
 * 怎么拆包、章节怎么排序、名字从哪儿来、包怎么拼，全在 `:core/book`（那边能脱机单测，
 * 也有 pandoc 与 ElementTree 各独立读同一份文件当裁判）；这里只管按需读文本、落盘、拼说明。
 * 读是**按需**的：一本书的图片能有几百 MB，只取那几份 XHTML。
 */
/** 一章的 XHTML 长过这个数，多半是把整包当一章读了（或这书本来就是扫描图）。 */
internal const val MAX_CHAPTER_BYTES = 32L * 1024 * 1024

/** 解压后的总长上限：zip 炸弹的常规防线，超了直说不做。 */
internal const val MAX_BOOK_BYTES = 512L * 1024 * 1024

class BookEngine(private val workspace: Workspace) {

    fun toText(item: WorkItem): EngineOutput = withBook(item) { book, notes ->
        val body = book.chapters.joinToString("\n\n") { chapter ->
            val rendered = Html.toPlainText(chapter.source)
            notes += rendered.notes
            rendered.text
        }
        require(body.isNotBlank()) { "这本书的正文都是图片，没有可抽的文字" }
        EngineOutput(
            OutputNaming.tagged(item.name, "文字", "txt"),
            writeText(item, body, "txt"),
            summary(book, body, "纯文本（没有标记）", notes),
        )
    }

    fun toMarkdown(item: WorkItem): EngineOutput = withBook(item) { book, notes ->
        val body = book.chapters.joinToString("\n\n") { chapter ->
            val rendered = Html.toMarkdown(chapter.source)
            notes += rendered.notes
            rendered.text
        }
        require(body.isNotBlank()) { "这本书的正文都是图片，没有可抽的文字" }
        EngineOutput(
            OutputNaming.tagged(item.name, "Markdown", "md"),
            writeText(item, body, "md"),
            summary(book, body, "Markdown", notes),
        )
    }

    /**
     * EPUB → Word。
     *
     * 章与章之间不插硬分页：Word 里跳章走样式面板，硬分页会让删改一处全篇错位。
     */
    fun toDocx(item: WorkItem): EngineOutput = withBook(item) { book, notes ->
        val parts = ArrayList<DocPart>()
        book.chapters.forEach { chapter ->
            val doc = Html.toDoc(chapter.source)
            notes += doc.notes
            parts += doc.parts
        }
        require(parts.isNotEmpty()) { "这本书按 spine 没排出任何正文（多半整本都是图片）" }
        val out = DocxWrite.document(Doc(parts, book.notes), modifiedAt = item.file.lastModified())
        val file = workspace.newStagingFile("docx").apply { writeBytes(out.bytes) }
        EngineOutput(
            OutputNaming.tagged(item.name, "Word", "docx"),
            file,
            "${book.chapters.size} 章 · ${parts.size} 块内容 · " +
                (book.notes + out.notes + merge(notes)).joinToString(" · "),
        )
    }

    /**
     * 文本 / Markdown / 网页 / Word 演示正文写成一份 `.epub`。
     *
     * 来源判定走 [SourceText]（与写成 Word 同一条，两个产物不会一个按记号排一个按空行排），
     * 切章与打包全在 `:core/book`（那边能脱机单测，产物另有 pandoc 与 ElementTree 当裁判）。
     * 书名空着用文件名、作者空着就不写 —— 编一个作者名进元数据，读者会当真去找这个人。
     */
    fun fromText(item: WorkItem, operation: Operation.TextToEpub): EngineOutput {
        val reading = SourceText.of(item)
        require(reading.doc.parts.isNotEmpty()) { "这里面没有可排的正文（空文件，或全是空白）" }
        val title = operation.title.ifBlank { OutputNaming.stem(item.name) }
        val author = operation.author.ifBlank { null }
        val pages = EpubWrite.chaptersOf(reading.doc, OutputNaming.stem(item.name))
        val language = EpubWrite.languageOf(reading.doc)
        val out = EpubWrite.book(
            title = title,
            author = author,
            pages = pages,
            language = language,
            identifier = EpubWrite.identifierFor(title + reading.source),
            modifiedAt = item.file.lastModified(),
        )
        val file = workspace.newStagingFile("epub").apply { writeBytes(out.bytes) }
        val chars = reading.source.count { !it.isWhitespace() }
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "epub"),
            file,
            "《$title》 · ${pages.size} 章 · $chars 字 · 语言标 $language · " +
                (reading.notes + reading.doc.notes + out.notes).joinToString(" · "),
        )
    }

    /** MOBI / AZW3 抽正文：PalmDOC 压缩在 `:core` 展开，这里只落盘与写说明。 */
    fun mobiToText(item: WorkItem): EngineOutput {
        val book = Mobi.read(item.file.readBytes())
        require(book.plainText.isNotBlank()) { "这本书的正文都是图片，没有可抽的文字" }
        val notes = ArrayList<String>()
        notes += "《${book.title}》" + (book.author?.let { " · $it" } ?: "")
        notes += "${book.plainText.count { !it.isWhitespace() }} 字"
        notes += book.notes
        return EngineOutput(
            OutputNaming.tagged(item.name, "文字", "txt"),
            writeText(item, book.plainText, "txt"),
            notes.filter { it.isNotBlank() }.joinToString(" · "),
        )
    }

    /** MOBI / AZW3 转 EPUB：正文是 HTML，按同一棵文档树还原标题、列表与表格。 */
    fun mobiToEpub(item: WorkItem): EngineOutput {
        val book = Mobi.read(item.file.readBytes())
        val doc = Html.toDoc(book.html)
        require(doc.parts.isNotEmpty()) { "正文里没有可排的段落（可能全是图片）" }
        val pages = EpubWrite.chaptersOf(doc, book.title)
        val language = EpubWrite.languageOf(doc)
        val out = EpubWrite.book(
            title = book.title,
            author = book.author,
            pages = pages,
            language = language,
            identifier = EpubWrite.identifierFor(book.title + book.html),
            modifiedAt = item.file.lastModified(),
        )
        val notes = ArrayList<String>()
        notes += "《${book.title}》"
        notes += "${pages.size} 章 · 语言标 $language"
        notes += book.notes
        notes += doc.notes
        notes += out.notes
        return EngineOutput(
            OutputNaming.tagged(item.name, "", "epub"),
            workspace.newStagingFile("epub").apply { writeBytes(out.bytes) },
            notes.filter { it.isNotBlank() }.joinToString(" · "),
        )
    }

    /** 打开包、按条目名惰性读出 XHTML，交给 `:core` 判断层排章节。 */
    private fun <T> withBook(item: WorkItem, block: (EpubBook, ArrayList<String>) -> T): T = openBook(item, block)

    /**
     * 一章一句"补了几处标签"会有几十行；同一种说法合并成一条并加总。
     *
     * 判据的说法都长成"N 处…"/"N 张…"，所以数字之后那段就是它的类型。
     */
    private fun merge(notes: List<String>): String {
        if (notes.isEmpty()) return ""
        val groups = LinkedHashMap<String, Long>()
        notes.forEach { note ->
            val number = note.takeWhile { it.isDigit() }
            val shape = if (number.isEmpty()) note else note.substring(number.length)
            groups[shape] = (groups[shape] ?: 0L) + (number.toLongOrNull() ?: 1L)
        }
        return groups.entries.joinToString(" · ") { (shape, total) ->
            if (shape.first().isDigit() || shape.startsWith(" ")) "$total$shape" else "$total 处$shape"
        }
    }

    private fun summary(book: EpubBook, body: String, target: String, notes: List<String>): String {
        val head = book.title?.let { "《$it》" } ?: "这本书"
        val author = if (book.author.isNullOrBlank()) "" else " · ${book.author}"
        val extra = (book.notes + merge(notes)).filter { it.isNotBlank() }
        return "$head · ${book.chapters.size} 章 · ${body.count { !it.isWhitespace() }} 字$author · 转成$target" +
            (if (extra.isEmpty()) "" else " · ${extra.joinToString(" · ")}")
    }

    private fun writeText(item: WorkItem, body: String, extension: String) =
        workspace.newStagingFile(extension).apply { writeText(body, Charsets.UTF_8) }
}

/**
 * 打开一本 EPUB：解压上限、单章上限、按 spine 排不出正文就拒 —— 这些规矩只该有一份。
 *
 * 摆成顶层函数是给「印成 PDF / 写成 Word / 写成电子书」那条共用入口（`SourceText`）用的：
 * 它在 class 外面拿不到 `withBook`，各写一套装载就会出现"一条路读出三章、另一路读出两章"的分歧。
 */
internal fun <T> openBook(item: WorkItem, block: (EpubBook, ArrayList<String>) -> T): T =
    FileSlices(item.file).use { slices ->
        val archive = ZipReader.read(slices, item.file.length())
        val sizes = archive.entries.sumOf { it.size }
        require(sizes <= MAX_BOOK_BYTES) {
            "这本书解压后有 ${sizes / 1024 / 1024} MB，超过 ${MAX_BOOK_BYTES / 1024 / 1024} MB 上限"
        }
        val bytes = HashMap<String, ByteArray>()
        val book = Epub.read(archive.entries.map { entry -> entry.name }) { name ->
            val entry = archive.entries.firstOrNull { it.name == name } ?: return@read null
            require(entry.size <= MAX_CHAPTER_BYTES) { "$name 有 ${entry.size / 1024 / 1024} MB，不像一章正文" }
            bytes.getOrPut(name) { ZipReader.dataOf(entry, slices) }
        }
        require(book.chapters.isNotEmpty()) {
            (book.notes + "按 spine 没排出一章正文来").joinToString(" · ")
        }
        block(book, ArrayList())
    }
