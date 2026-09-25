package com.fileforge.core.doc

/** 建树时顺手数出来的"这网页的标签写得不规矩"的次数，用于向用户交代。 */
internal class HtmlBuilt(val root: HtmlNode, val repaired: Int)

internal class HtmlNode(val name: String, val attrs: Map<String, String> = emptyMap()) {
    val children = ArrayList<HtmlNode>()
    var text: String = ""
    fun isText() = name == "#text"
    fun raw() = name.startsWith("#raw:")
}

/**
 * 把 token 流搭成一棵树，按浏览器那样补全没闭合的标签。
 *
 * 只补最常用那几条（`<p>` 遇到块级元素就收、同级的 li / td / th / dt / dd / 标题互相收尾），
 * 多余的收尾标签忽略而不是报错。补了几处要记下来：那说明源文件本身写坏了，
 * 结构可能不是作者想的那样。
 */
internal object HtmlTree {

    val BLOCKS = setOf(
        "address", "article", "aside", "blockquote", "dd", "details", "div", "dl", "dt",
        "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5",
        "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "table",
        "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
    )

    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    fun build(tokens: List<HtmlToken>): HtmlBuilt {
        val root = HtmlNode("#root")
        val open = ArrayList<HtmlNode>()
        open += root
        var repaired = 0

        fun closeUntil(name: String) {
            while (open.size > 1 && open.last().name == name) {
                open.removeAt(open.size - 1)
                repaired++
            }
        }

        tokens.forEach { token ->
            when (token) {
                is HtmlText -> open.last().children += HtmlNode("#text").apply { text = token.text }
                is HtmlRaw -> open.last().children += HtmlNode("#raw:" + token.name).apply { text = token.text }
                is HtmlOther -> Unit
                is HtmlTag -> when {
                    token.closing -> {
                        val at = open.indexOfLast { it.name == token.name }
                        if (at < 1) {
                            repaired++                                    // 多余的收尾标签：忽略掉别把父节点带跑
                        } else {
                            while (open.size - 1 > at) {
                                open.removeAt(open.size - 1)
                                repaired++
                            }
                            open.removeAt(open.size - 1)
                        }
                    }
                    else -> {
                        val name = token.name
                        if (name in BLOCKS && open.last().name == "p") closeUntil("p")
                        if (name == "li" && open.last().name == "li") closeUntil("li")
                        if (name in setOf("td", "th") && open.last().name in setOf("td", "th")) closeUntil(open.last().name)
                        if (name == "tr" && open.last().name in setOf("td", "th", "tr")) {
                            while (open.size > 1 && open.last().name != "table") closeUntil(open.last().name)
                        }
                        if (name in setOf("dt", "dd") && open.last().name in setOf("dt", "dd")) closeUntil(open.last().name)
                        if (name in HEADINGS && open.last().name in HEADINGS) closeUntil(open.last().name)
                        if (name == "option" && open.last().name == "option") closeUntil("option")
                        val node = HtmlNode(name, token.attrs)
                        open.last().children += node
                        if (name !in HtmlTokens.VOID && !token.selfClosing) open += node
                    }
                }
            }
        }
        repaired += open.size - 1                                         // 到文件末尾还开着的
        return HtmlBuilt(root, repaired)
    }
}
