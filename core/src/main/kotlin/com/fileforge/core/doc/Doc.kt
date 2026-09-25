package com.fileforge.core.doc

/**
 * 一段里的一段文字。
 *
 * `link` 非空表示这一段要写成 Word 里能点开的真链接（地址在文档外的关联表里，一处一份）；
 * `mono` 是给代码用的等宽记号 —— Word 里换字体就够，不需要额外部件。
 */
data class DocRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val mono: Boolean = false,
    val underline: Boolean = false,
    val link: String? = null,
)

/**
 * 一个段落。
 *
 * `style` 是 Word 认的样式名（`Heading1..6` / `Quote` / `SourceCode` / `ListParagraph`）；
 * `bullet` 非空表示这是列表项：`true` 是点号列表、`false` 是编号列表，`indent` 是层号 ——
 * 记号由 Word 自己画（numbering.xml 里定义），不是写在文字里的。写进文字的话，Word 里
 * 删一行序号就乱了，别的编辑器也不会把它认成列表。
 */
data class DocPara(
    val runs: List<DocRun>,
    val style: String = "Body",
    val indent: Int = 0,
    val bullet: Boolean? = null,
) {
    val text: String get() = runs.joinToString("") { it.text }
}

/** 文档里的一块：段落、分隔线或表格。 */
sealed interface DocPart

class DocParagraph(val para: DocPara) : DocPart

/** 分隔线：Word 里没有"横线"这个块，用的是带下边线的空段（pandoc 也这么写）。 */
class DocRule : DocPart

/** 一张表：`header` 表示第一行是表头（Word 会把它加粗并在跨页时重复）。 */
class DocTable(val header: Boolean, val rows: List<List<String>>) : DocPart

/**
 * 一份"能印成 Word 的文档"。
 *
 * 为什么要中间夹这一层：纯文本、Markdown、网页三种来源各有各的解析器，而"排成 Word"只该有一套。
 * 三处各自拼 OOXML，就会得到三份在 Word 里长得不一样的文件 —— 这一层让它们共用一个排法。
 */
class Doc(val parts: List<DocPart>, val notes: List<String>) {
    /** 文档里出现过的链接地址（去重、保序），写文档外的关联表时按这个序号发号。 */
    val links: List<String>
        get() {
            val out = ArrayList<String>()
            parts.forEach { part ->
                if (part is DocParagraph) part.para.runs.forEach { run ->
                    val target = run.link
                    if (target != null && target !in out) out += target
                }
            }
            return out
        }
}

/**
 * 普通文字 → 文档树。
 *
 * 规矩只有一条：**空行分段**，段内的换行留在段里（Word 里是软回车）。
 * 不这么分的话，一整篇会成一段（粘进 Word 是一根长直线），或者每一行一段（段后距叠出一屏空白）。
 */
object PlainDoc {

    fun toDoc(source: String): Doc {
        val notes = ArrayList<String>()
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        if (source != normalized) notes += "换行风格统一成了 LF（Word 里换行不分 CRLF）"
        val parts = ArrayList<DocPart>()
        normalized.split(Regex("\n{2,}")).forEach { block ->
            val text = block.trim('\n')
            if (text.isNotBlank()) parts += DocParagraph(DocPara(listOf(DocRun(text.trimEnd()))))
        }
        if (parts.isEmpty()) notes += "这份文件里没有正文（全是空行）"
        return Doc(parts, notes)
    }
}

/**
 * 一栏文本 → 文档树：按**内容**挑路，不看扩展名。
 *
 * 两边都像时按"有没有网页的骨架"分：`<div>`、`<table>` 说明这份文件是按网页搭起来的；
 * 而 `<br>`、`<img>` 在 Markdown 稿子里太常见 —— 为了一个换行标签把整篇 `#` 打回字面文字，
 * 是最容易咬到写作者的一种错。反过来（网页里恰好有 `- ` 开头的一行）代价是整页散架，更重。
 */
object TextDoc {

    enum class Route { Web, Markdown, Plain }

    /** 走成的文档，以及"按哪条路排的"（结果说明里要写，用户才知道为什么列表变成了点号）。 */
    class Reading(val doc: Doc, val route: Route)

    /** 只有这些算网页的骨架。行内标签（`<b>` `<a>` `<sub>` `<img>`）不在列。 */
    private val SKELETON = listOf(
        "<!doctype", "<html", "<head", "<body", "<div", "<p>", "</p>", "<table", "<ul", "<ol",
        "<h1", "<h2", "<h3", "<h4", "<h5", "<h6", "<blockquote", "<section", "<article", "<header",
    )

    fun read(source: String): Reading {
        val markdown = Markdown.looksLikeMarkdown(source)
        val web = Html.looksLikeHtml(source) &&
            (!markdown || SKELETON.any { mark -> source.contains(mark, ignoreCase = true) })
        if (web) return Reading(Html.toDoc(source), Route.Web)
        if (markdown) {
            val page = Markdown.toHtml(source)
            val parsed = Html.toDoc(page.text)
            return Reading(Doc(parsed.parts, page.notes + parsed.notes), Route.Markdown)
        }
        return Reading(PlainDoc.toDoc(source), Route.Plain)
    }
}

