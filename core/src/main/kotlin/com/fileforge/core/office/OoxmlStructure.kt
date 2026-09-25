package com.fileforge.core.office

import org.w3c.dom.Element
import org.w3c.dom.Node

/** 按文件自己的声明排出来的部件顺序；notes 说清有几份是靠边补的。 */
class DeclaredParts(val parts: List<String>, val notes: List<String>)

/**
 * OOXML 的"顺序"不写在文件名里，写在主部件的大纲里，而大纲只给 `r:id`，
 * 真部件名还要再过一层关系表。
 *
 * 演示文稿的页序是 `p:sldIdLst` 里的 `p:sldId`。照文件名排是错的：第十页之后会排成
 * slide1、slide10、slide11、slide2。工作簿的表序同理，但它连表名一起带，见 `Xlsx.sheets`。
 */
object OoxmlStructure {
    const val DECK = "ppt/presentation.xml"
    const val DECK_RELS = "ppt/_rels/presentation.xml.rels"
    const val WORKBOOK = "xl/workbook.xml"
    const val WORKBOOK_RELS = "xl/_rels/workbook.xml.rels"

    /** 幻灯片部件，按演示大纲里的先后。 */
    fun slideOrder(load: (String) -> ByteArray?, available: Collection<String>): DeclaredParts {
        val present = available.filterTo(LinkedHashSet()) { it.startsWith("ppt/slides/") && it.endsWith(".xml") }
        val targets = load(DECK_RELS)?.let { relationships(it, base = "ppt") }.orEmpty()
        val ids = load(DECK)?.let { main ->
            findAll(OoxmlXml.root(main), "sldId").mapNotNull { referenceId(it) }
        }.orEmpty()
        val ordered = LinkedHashSet<String>()
        val notes = ArrayList<String>()
        var dangling = 0
        ids.forEach { id ->
            val target = targets[id]
            if (target != null && target in present) ordered += target else dangling++
        }
        if (dangling > 0) notes += "大纲里声明的 $dangling 页在包里找不到，只能跳过"
        val leftover = present - ordered
        if (leftover.isNotEmpty()) {
            ordered += leftover.sorted()
            notes += "有 ${leftover.size} 页没在大纲里认出来，按文件名补在了后面"
        }
        return DeclaredParts(ordered.toList(), notes)
    }

    /** 关系表：Id → 部件在包里的绝对名。目标是相对源部件所在目录写的，所以要按 base 折算。 */
    internal fun relationships(rels: ByteArray, base: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        findAll(OoxmlXml.root(rels), "Relationship").forEach { element ->
            val id = element.getAttribute("Id")
            val target = element.getAttribute("Target")
            if (id.isNotEmpty() && target.isNotEmpty()) out[id] = resolve(base, target)
        }
        return out
    }

    /**
     * 引用属性在文件里写作 `r:id`，前缀理论上是那份文件自己定的，所以按"本名是 id"认。
     * `sheetId` 这类别的属性本名不叫 id，不会被误抓。
     */
    internal fun referenceId(element: Element): String? {
        val attributes = element.attributes
        var fallback: String? = null
        for (index in 0 until attributes.length) {
            val name = attributes.item(index).nodeName
            if (name != "id" && !name.endsWith(":id")) continue
            val value = attributes.item(index).nodeValue
            if (value.startsWith("rId")) return value
            fallback = value
        }
        return fallback
    }

    /** 整棵子树里按文档顺序找出本名匹配的元素。 */
    internal fun findAll(root: Element, local: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(node: Node) {
            if (node is Element && localName(node) == local) out += node
            val children = node.childNodes
            for (index in 0 until children.length) walk(children.item(index))
        }
        walk(root)
        return out
    }

    /** 直接孩子里按本名找（不往下钻）：`t`、`si` 这类名字会在好几层都出现，只该收这一层。 */
    internal fun childrenOf(node: Node, local: String): List<Element> {
        val out = ArrayList<Element>()
        val children = node.childNodes
        for (index in 0 until children.length) {
            val item = children.item(index)
            if (item is Element && localName(item) == local) out += item
        }
        return out
    }

    internal fun localName(node: Node): String =
        (node as? Element)?.let { it.localName ?: it.tagName.substringAfter(':') } ?: ""

    /** 目标是 `slides/slide1.xml` 这种相对写法，也可能是 `/ppt/slides/slide1.xml` 这种绝对写法。 */
    private fun resolve(base: String, target: String): String = when {
        target.startsWith("/") -> target.removePrefix("/")
        else -> "$base/${target.removePrefix("./")}"
    }
}
