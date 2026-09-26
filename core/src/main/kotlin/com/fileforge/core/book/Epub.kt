package com.fileforge.core.book

import com.fileforge.core.doc.EntityTally
import com.fileforge.core.doc.HtmlNode
import com.fileforge.core.doc.HtmlTokens
import com.fileforge.core.doc.HtmlTree

/** 一章：显示用的标题、那份 XHTML、以及它在包里的路径（说明里要指着它说话）。 */
class EpubChapter(val title: String, val source: String, val part: String)

/** 拆开的书：书名与作者是可选的，章节已按阅读顺序排好，notes 是"有什么没搬"。 */
class EpubBook(
    val title: String?,
    val author: String?,
    val chapters: List<EpubChapter>,
    val images: Int,
    val notes: List<String>,
)

/**
 * EPUB 的读入侧：`.epub` 就是一个 zip，里面 `META-INF/container.xml` 指向 OPF，
 * OPF 的 manifest 列出所有部件、**spine 才规定阅读顺序** —— 文件名顺序是另一回事。
 *
 * 这里只把"哪几份 XHTML、按什么顺序、每章该叫什么"这套规矩实现出来，
 * 正文的解析继续用 `core/doc` 那份容错 HTML 读取（三处输出共用一套，不会这边是列表那边是段落）。
 *
 * XML 一律不请外部工具：用自家那套容错 tokenizer 读，它**不会去访问任何地址**，
 * 也不解析 DTD —— 书是别人做的，OPF 里写什么都有可能的原则同上（XML 那族带 DTD 一律不解析）。
 */
object Epub {

    private val DOCUMENT_TYPES = setOf("application/xhtml+xml", "text/html", "application/xml")

    /** 测试与调用方的便捷入口：整包都在手上时。 */
    fun read(entries: Map<String, ByteArray>): EpubBook = read(entries.keys.toList(), entries::get)

    /** 条目名 + 按需取内容：一本书的图片能有几百 MB，判断层只需要那几份文本，所以不预先全读进来。 */
    fun read(names: List<String>, find: (String) -> ByteArray?): EpubBook {
        val notes = ArrayList<String>()
        val index = HashMap<String, String>()
        names.forEach { name -> index[name.lowercase()] = name }
        fun entry(path: String): ByteArray? =
            find(path) ?: index[path.lowercase()]?.let(find)

        val wanted = names.firstOrNull { it.equals("META-INF/container.xml", ignoreCase = true) }
        if (wanted == null) {
            // 加了 DRM 的包把整个目录挪进 encrypted/ 那一层：它确实是 EPUB，只是正文读不出来
            if (names.any { it.startsWith("encrypted/", ignoreCase = true) }) {
                throw IllegalArgumentException("这本 EPUB 带着 DRM（包里有 encrypted/ 那一层），正文读不出来")
            }
            throw IllegalArgumentException(
                "这份包里找不到 META-INF/container.xml（或它没写 rootfile），它不是 EPUB",
            )
        }
        val container = entry(wanted)?.let { String(decode(it), Charsets.UTF_8) }.orEmpty()
        val rootfile = attributes(container).firstOrNull { it["tag"] == "rootfile" }?.get("full-path")
            ?: throw IllegalArgumentException(
                "这份包里找不到 META-INF/container.xml（或它没写 rootfile），它不是 EPUB",
            )
        val opfPath = normalize("", urlDecode(rootfile)).removePrefix("/")
        val opfBytes = entry(opfPath)
            ?: throw IllegalArgumentException("container 指向的 $opfPath 不在包里，这份 EPUB 缺件")
        val opf = String(decode(opfBytes), Charsets.UTF_8)

        val parts = LinkedHashMap<String, String>()
        val titles = HashMap<String, String>()
        var bookTitle: String? = null
        var author: String? = null
        var images = 0
        val order = ArrayList<String>()
        attributes(opf).forEach { attrs ->
            when (attrs.getValue("tag")) {
                "title" -> if (bookTitle == null) bookTitle = attrs["__text"]?.ifBlank { null }
                "creator" -> if (author == null) author = attrs["__text"]?.ifBlank { null }
                "item" -> {
                    val id = attrs["id"] ?: return@forEach
                    val href = attrs["href"] ?: return@forEach
                    val type = attrs["media-type"].orEmpty()
                    val path = normalize(opfPath, urlDecode(href))
                    if (type == "application/x-dtbncx+xml") {
                        titles.putAll(navLabels(entry(path), opfPath))
                    } else if (type.startsWith("image/")) {
                        images++
                    } else if (type in DOCUMENT_TYPES) {
                        parts[id] = path
                    }
                }
                "itemref" -> {
                    val id = attrs["idref"] ?: return@forEach
                    if (attrs["linear"].equals("no", ignoreCase = true)) return@forEach
                    order += id
                }
            }
        }

        val chapters = ArrayList<EpubChapter>()
        var missing = 0
        var unknown = 0
        order.forEach { id ->
            val path = parts[id]
            if (path == null) {
                unknown++
                return@forEach
            }
            val bytes = entry(path)
            if (bytes == null) {
                missing++
                return@forEach
            }
            val source = String(decode(bytes), Charsets.UTF_8)
            chapters += EpubChapter(titleOf(source, titles[path], path.substringAfterLast('/')), source, path)
        }
        if (missing > 0) notes += "$missing 章的文件不在包里（EPUB 缺件），跳过了"
        if (unknown > 0) notes += "$unknown 个 spine 条目在清单里没有对应的项，跳过了"
        if (images > 0) notes += "$images 张内嵌图片不是文字，没搬"
        val unused = parts.size - chapters.size - missing
        if (unused > 0) notes += "$unused 个清单里的部件没被 spine 用到，没当正文"
        if (chapters.isEmpty()) notes += "按 spine 没排出一章来（这份书的顺序信息是空的）"
        return EpubBook(bookTitle, author, chapters, images, notes)
    }

    /**
     * NCX（EPUB2 的目录）给每章一个人类可读的名字。
     *
     * 规范里的顺序是 `<navPoint><navLabel><text>名</text></navLabel><content src="…"/></navPoint>` ——
     * **名字在路径前面**，所以只能一个 navPoint 一组地攒，不能"见到 content 就认下一个 text"。
     */
    private fun navLabels(bytes: ByteArray?, base: String): Map<String, String> {
        if (bytes == null) return emptyMap()
        val source = String(decode(bytes), Charsets.UTF_8)
        val out = HashMap<String, String>()
        var label: String? = null
        var path: String? = null
        fun flush() {
            val from = path
            val text = label
            if (from != null && text != null) out.putIfAbsent(from, text)
            label = null
            path = null
        }
        attributes(source).forEach { attrs ->
            when (attrs.getValue("tag").lowercase()) {
                "navpoint" -> flush()
                "text" -> if (label == null) label = attrs["__text"]?.trim()?.ifNotEmptyOrNull()
                "content" -> if (path == null) {
                    path = attrs["src"]?.let { normalize(base, urlDecode(it)).removePrefix("/") }
                }
            }
        }
        flush()
        return out
    }

    private fun String.ifNotEmptyOrNull(): String? = if (isEmpty()) null else this

    /** 名字的来源按优先级：目录(NCX)给的 > 文档自己的 title > 第一个标题 > 文件名。 */
    private fun titleOf(source: String, given: String?, fallback: String): String {
        if (!given.isNullOrBlank()) return given
        val nodes = attributes(source)
        return nodes.firstOrNull { it["tag"] == "title" }?.get("__text")?.trim()?.ifNotEmpty()
            ?: nodes.firstOrNull { it["tag"]?.matches(Regex("h[1-6]")) == true }?.get("__text")?.trim()?.ifNotEmpty()
            ?: fallback
    }

    /**
     * 把 XHTML 摊平成"元素 + 属性 + 元素正文"的扁表，按文档顺序。
     *
     * 只用到 OPF / container / NCX 这几份元数据，属性都是简单值；
     * 正文不用这里 —— 那边有 [com.fileforge.core.doc.Html] 一套完整的。
     */
    private fun attributes(source: String): List<Map<String, String>> {
        val built = HtmlTree.build(HtmlTokens.tokenize(source, EntityTally()))
        val out = ArrayList<Map<String, String>>()
        collect(built.root, out)
        return out
    }

    private fun collect(node: HtmlNode, into: ArrayList<Map<String, String>>) {
        if (node.raw()) {
            // `title` 是"原始文本元素"：孩子拿到的是一整段（含开收标签），事件流里不会再有 #text
            val name = node.name.substringAfter(':')
            into += mapOf("tag" to name, "__text" to rawText(node.text, name))
            return
        }
        if (!node.isText()) {
            val attrs = HashMap(node.attrs)
            attrs["tag"] = node.name.substringAfterLast(':')          // 命名空间前缀（dc:title）去掉再比
            val text = StringBuilder()
            node.children.forEach { child -> if (child.isText()) text.append(child.text) }
            if (text.isNotEmpty()) attrs["__text"] = text.toString()
            into += attrs
        }
        node.children.forEach { collect(it, into) }
    }

    private fun rawText(source: String, name: String): String {
        val open = source.indexOf('>')
        val body = if (open >= 0) source.substring(open + 1) else source
        val close = body.lastIndexOf("</$name", ignoreCase = true)
        return if (close >= 0) body.substring(0, close) else body
    }

    /** href 可能是相对 OPF 的、可能带片段、可能 `..` 往上跳；全部折成包内的规范路径。 */
    private fun normalize(base: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        val joined = if (clean.startsWith("/")) clean
        else base.substringBeforeLast('/', "") + "/" + clean
        val out = ArrayList<String>()
        joined.split('/').forEach { piece ->
            when {
                piece.isEmpty() || piece == "." -> Unit
                piece == ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += piece
            }
        }
        return out.joinToString("/")
    }

    private fun urlDecode(value: String): String {
        if (!value.contains('%') && !value.contains('+')) return value
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toInt().toChar())      // 路径里的非 ASCII 少，先按单字节解
                    i += 3
                    continue
                }
            }
            out.append(if (ch == '+') " " else ch)
            i++
        }
        return out.toString()
    }

    /**
     * 内容文档可以是 UTF-8 或 UTF-16（BOM 说了算；没带 BOM 时 XML 声明里的 encoding 说了算）。
     *
     * 按错的编码读会整章变乱码，所以这里除了 BOM 还看 UTF-16 那个"每隔一字节是 0"的形状 ——
     * 有些生产工具写 UTF-16 不带 BOM。
     */
    private fun decode(bytes: ByteArray): ByteArray {
        if (bytes.size < 4) return bytes
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        val charset = when {
            b0 == 0xFF && b1 == 0xFE -> Charsets.UTF_16LE
            b0 == 0xFE && b1 == 0xFF -> Charsets.UTF_16BE
            b1 == 0 && b3 == 0 && b0 != 0 && b2 != 0 -> Charsets.UTF_16LE
            b0 == 0 && b2 == 0 && b1 != 0 && b3 != 0 -> Charsets.UTF_16BE
            else -> return bytes
        }
        return String(bytes, charset).toByteArray(Charsets.UTF_8)
    }

    private fun String.ifNotEmpty(): String? = if (isEmpty()) null else this

}
