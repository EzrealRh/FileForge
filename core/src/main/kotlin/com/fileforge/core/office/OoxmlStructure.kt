package com.fileforge.core.office

import org.w3c.dom.Element
import org.w3c.dom.Node

/** 按文件自己的声明排出来的部件顺序；notes 说清有几份是靠边猜出来的。 */
class DeclaredParts(val parts: List<String>, val notes: List<String>)

/**
 * OOXML 的"顺序"不写在文件名里，写在主部件的大纲里。
 *
 * 演示文稿的页序是 `p:sldIdLst`，工作簿的表序是 `sheets` —— 两边都是"`r:id` 指向关系表，
 * 关系表再指向真部件"。照文件名排是错的：第十页之后会排成 slide1、slide10、slide11、slide2。
 */
object OoxmlStructure {
    const val DECK = "ppt/presentation.xml"
    const val DECK_RELS = "ppt/_rels/presentation.xml.rels"
    const val WORKBOOK = "xl/workbook.xml"
    const val WORKBOOK_RELS = "xl/_rels/workbook.xml.rels"

    fun slideOrder(load: (String) -> ByteArray?, available: Collection<String>): DeclaredParts =
        order(load, DECK, DECK_RELS, base = "ppt", refElement = "sldId", available = available,
            matches = { it.startsWith("ppt/slides/") && it.endsWith(".xml") }, unit = "页")

    fun sheetOrder(load: (String) -> ByteArray?, available: Collection<String>): DeclaredParts =
        order(load, WORKBOOK, WORKBOOK_RELS, base = "xl", refElement = "sheet", available = available,
            matches = { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }, unit = "表")

    private fun order(
        load: (String) -> ByteArray?,
        mainPart: String,
        relsPart: String,
        base: String,
        refElement: String,
        available: Collection<String>,
        matches: (String) -> Boolean,
        unit: String,
    ): DeclaredParts {
        val present = available.filterTo(LinkedHashSet()) { matches(it) }
        val targets = load(relsPart)?.let { relationships(it, base) }.orEmpty()
        val notes = ArrayList<String>()
        val ordered = LinkedHashSet<String>()
        var dangling = 0
        load(mainPart)?.let { main ->
            references(main, refElement).forEach { id ->
                val target = targets[id]
                when {
                    target != null && target in present -> ordered += target
                    else -> dangling++
                }
            }
        }
        if (dangling > 0) notes += "大纲里声明的 $dangling ${unit}在包里找不到，只能跳过"
        val leftover = present - ordered
        if (leftover.isNotEmpty()) {
            ordered += leftover.sorted()
            notes += "有 ${leftover.size} ${unit}没在大纲里认出来，按文件名补在了后面"
        }
        return DeclaredParts(ordered.toList(), notes)
    }

    /** 关系表：Id → 部件在包里的绝对名。目标是相对源部件所在目录的，所以要按 base 折算。 */
    internal fun relationships(rels: ByteArray, base: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        eachElement(OoxmlXml.root(rels)) { element ->
            if (local(element) != "Relationship") return@eachElement
            val id = element.getAttribute("Id")
            val target = element.getAttribute("Target")
            if (id.isNotEmpty() && target.isNotEmpty()) out[id] = resolve(base, target)
        }
        return out
    }

    /** 主部件里引用关系的顺序（文档顺序就是声明顺序）。 */
    internal fun references(main: ByteArray, elementName: String): List<String> {
        val out = ArrayList<String>()
        eachElement(OoxmlXml.root(main)) { element ->
            if (local(element) != elementName) return@eachElement
            referencedId(element)?.let { out += it }
        }
        return out
    }

    /** 目标是 `slides/slide1.xml` 这种相对写法，也可能是 `/ppt/slides/slide1.xml` 这种绝对写法。 */
    private fun resolve(base: String, target: String): String = when {
        target.startsWith("/") -> target.removePrefix("/")
        else -> "$base/${target.removePrefix("./")}"
    }

    /**
     * 引用属性在文件里写作 `r:id`，前缀理论上由那份文件自己定，所以按"本名是 id"认。
     * `sheetId`、`id` 这种同前缀的别的属性本名不叫 id，不会被误抓。
     */
    private fun referencedId(element: Element): String? {
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

    private fun local(node: Node): String = (node as? Element)?.let { it.localName ?: it.tagName.substringAfter(':') } ?: ""

    private fun eachElement(node: Node, visit: (Element) -> Unit) {
        if (node is Element) visit(node)
        val children = node.childNodes
        for (index in 0 until children.length) eachElement(children.item(index), visit)
    }
}
