package com.fileforge.core.data

import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonArray
import com.fileforge.core.json.JsonNull
import com.fileforge.core.json.JsonNumber
import com.fileforge.core.json.JsonObject
import com.fileforge.core.json.JsonRender
import com.fileforge.core.json.JsonString
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Comment
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.ProcessingInstruction
import org.xml.sax.InputSource

class XmlException(message: String) : Exception(message)

/**
 * XML 与 JSON 的互转。
 *
 * 两套结构的对应关系是**约定**，不是标准，所以这里把规则定死并写进界面：
 *  - 元素名一律映射成**数组**（哪怕只有一个）—— 这样"几个孩子"这个信息不会丢，转回去还数得出来。
 *    另一种常见做法（一个当对象、多个当数组）会让下游每读一个字段都得先判它到底是不是数组
 *  - 元素带属性时变成对象：属性名前面加 `@`，元素自己的文字放在 `#text`
 *  - 只有文字的元素直接就是那个字符串；空元素是 null
 *  - 注释与处理指令丢掉；DTD 整个拒（见 [parse]，那是安全要求不是格式偏好）
 *  - 命名空间前缀原样留在名字里（`xmlns` 声明会被当成普通属性），不展开成 URI ——
 *    展开需要知道每个前缀的语义，而 JSON 那边没地方放
 */
object Xml {

    const val TEXT = "#text"
    private const val ATTRIBUTE_PREFIX = "@"

    /** 解析成 JSON。最外层是一个只有一项的对象：`{"根元素名": ...}`。 */
    fun parse(text: String): Json {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        factory.isXIncludeAware = false
        factory.setExpandEntityReferences(false)
        // 三件套缺一不可：外部实体会让转换工具去访问声明里写的地址（XXE），
        // 内部实体会被无限展开吃光内存。关掉 DTD 支持是最干脆的挡法。
        // 两道防线，缺一道都不算挡住：
        //  1) 解析器自己的开关。外部实体会让"转换工具"去访问 DTD 里写的地址（XXE），
        //     内部实体会被无限展开吃光内存。
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (feature, value) ->
            runCatching { factory.setFeature(feature, value) }
        }
        //  2) 不看解析器脸色的裸判定：上面那些开关在部分安卓解析器上根本不支持，
        //     "设不上"不能当成"挡住了"。DTD 只允许出现在序文里，所以扫一遍源文本就够。
        rejectDoctype(text)
        val document = try {
            factory.newDocumentBuilder().parse(InputSource(ByteArrayInputStream(text.toByteArray())))
        } catch (bad: Exception) {
            val reason = bad.message?.takeIf { it.isNotBlank() } ?: bad.javaClass.simpleName
            throw XmlException("这不是合法 XML：$reason")
        }
        val root = document.documentElement ?: throw XmlException("这份 XML 里没有元素")
        return JsonObject(linkedMapOf(root.tagName to elementToJson(root)))
    }

    /** 带 DTD 的一律不解析：实体展开这件事没法在"部分解析器支持开关"的前提下保证安全。 */
    fun rejectDoctype(text: String) {
        val head = text.take(minOf(text.length, 8 * 1024))
        if (head.contains("<!DOCTYPE", ignoreCase = true) || head.contains("<!ENTITY", ignoreCase = true)) {
            throw XmlException("这份 XML 带 DTD 或实体定义，不解析：实体展开能让转换工具去访问别人的地址或把内存吃光")
        }
        if (head.contains("SYSTEM", ignoreCase = true) && head.contains("<!", ignoreCase = true)) {
            throw XmlException("这份 XML 引用了外部定义（SYSTEM），不解析")
        }
    }

    private fun elementToJson(element: Element): Json {
        val children = LinkedHashMap<String, MutableList<Json>>()
        val text = StringBuilder()
        var sawElement = false
        var sawComment = false
        val kids = element.childNodes
        for (i in 0 until kids.length) {
            when (val node = kids.item(i)) {
                is Element -> {
                    sawElement = true
                    children.getOrPut(node.tagName) { ArrayList() } += elementToJson(node)
                }
                is Comment, is ProcessingInstruction -> sawComment = true
                else -> if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                    text.append(node.nodeValue)
                }
            }
        }
        val attributes = ArrayList<Pair<String, String>>()
        val named = element.attributes
        for (i in 0 until named.length) {
            attributes += named.item(i).nodeName to named.item(i).nodeValue
        }
        // DOM 不保证属性的返回顺序（安卓那套与桌面那套就给得不一样），排一下才让
        // 同一份 XML 在任何平台上转出同一列顺序 —— 子元素不排，那是文档顺序，排了就改数据
        attributes.sortBy { it.first }
        val body = text.toString().trim()
        if (attributes.isEmpty() && !sawElement) {
            // 纯文字或空元素。注释按约定一律丢掉，界面上会提前说明
            return if (body.isEmpty()) JsonNull else JsonString(body)
        }
        val members = LinkedHashMap<String, Json>()
        attributes.forEach { (name, value) -> members[ATTRIBUTE_PREFIX + name] = JsonString(value) }
        if (body.isNotEmpty()) members[TEXT] = JsonString(body)
        children.forEach { (name, values) -> members[name] = JsonArray(values) }
        return JsonObject(members)
    }

    /** 名字得是合法 XML 标签：首字符不能是数字或连字符，也不能空。 */
    fun isElementName(name: String): Boolean =
        name.isNotEmpty() && (name.first().isLetter() || name.first() == '_' || name.first() == ':') &&
            name.all { it.isLetterOrDigit() || it in "._-:" }

    /**
     * 渲染会用的两个名字：根元素名，以及顶层数组那层子元素名（顶层不是数组时 null）。
     *
     * 单独拿出来是因为产物说明也要说同一件事 —— 两边各判一次，早晚一个说 `<书>` 一个说 `<item>`。
     *
     * 只有一个键、键的值是数组时按数组长度分两种：
     *  - 0 或 1 个：那个键就是根元素（`<书>…</书>`、空的是 `<书/>`）。读的那一侧把一个根元素收成
     *    "键 → 只有一个元素的数组"，所以这条是 `render(parse(x)) == x` 成立的前提 —— 不这么分，
     *    自己写的 XML 读回来再写一遍会多套一层同名元素。
     *  - 2 个以上：一个文档只能有一个根元素，所以外面包一层 [root]，每项写成以那个键命名的子元素。
     * 顶层直接是数组（没有键可用）时同理包一层，子元素叫 [item]。
     */
    fun namesFor(value: Json, root: String = "root", item: String = "item"): Pair<String, String?> {
        val only = (value as? JsonObject)?.members?.takeIf { it.size == 1 }
        if (only != null) {
            val key = only.keys.first()
            val list = only.values.first()
            if (list !is JsonArray || list.arrayValue.size <= 1) return key to null
            return root to key
        }
        return root to if (value is JsonArray) item else null
    }

    /**
     * JSON / 那棵共用的树 → XML。
     *
     * **一份 XML 文档只能有一个根元素**，所以顶层是数组且有两项以上时必须包一层：根元素用 [root]
     * 这个名字，每一项写成 [item] 或那个键命名的子元素。以前这里不包 —— 两项以上就写出两个并排的
     * 根元素，那种文件任何解析器都只认前半截（ElementTree 报 "junk after document element"）。
     *
     * 对象只有一个键时那个键当根元素（键的值是"最多一个元素"的数组也算 —— 见 [namesFor]）。
     * 嵌套层的数组仍写成同名重复元素 —— 读的那一侧把重复元素收成数组，两边是同一条约定。
     */
    fun render(value: Json, root: String = "root", item: String = "item", indent: Int = 2): String {
        val (rootName, childName) = namesFor(value, root, item)
        // 只有"一个键的对象"才拆掉那一层；多个键的对象必须整个当根元素的内容 ——
        // 早先这里写成 members.values.firstOrNull()，多键的 YAML 转过来只剩第一个键，别的键静悄悄没了
        val only = (value as? JsonObject)?.members?.takeIf { it.size == 1 }
        val rootValue = if (only != null) only.values.first() else value
        require(isElementName(rootName)) { "根元素名「$rootName」不能当 XML 标签用：换个字母开头、不含空格与尖括号的名字" }
        if (childName != null) {
            require(isElementName(childName)) { "子元素名「$childName」不能当 XML 标签用：换个字母开头、不含空格与尖括号的名字" }
        }
        // 顶层数组要有人包着；包法用现成的"对象的一个键指向数组"那条渲染路，行为与嵌套层一致
        val body = if (childName == null) rootValue else JsonObject(linkedMapOf(childName to rootValue))
        val builder = StringBuilder()
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writeElement(builder, rootName, body, 0, if (indent > 0) indent else 0)
        return builder.toString()
    }

    private fun writeElement(out: StringBuilder, name: String, value: Json, depth: Int, indent: Int) {
        val pad = if (indent > 0) " ".repeat(indent * depth) else ""
        val newline = if (indent > 0) "\n" else ""
        when {
            value is JsonArray -> {
                // 空数组也得留一个空元素：什么都不写等于这个字段在 XML 里凭空消失
                if (value.arrayValue.isEmpty()) out.append(pad).append('<').append(name).append("/>").append(newline)
                value.arrayValue.forEach { child -> writeElement(out, name, child, depth, indent) }
            }
            value.isNull -> out.append(pad).append('<').append(name).append("/>").append(newline)
            value.stringValue != null -> {
                out.append(pad).append('<').append(name).append('>')
                out.append(escapeText(value.stringValue ?: ""))
                out.append("</").append(name).append('>').append(newline)
            }
            value.numberText != null -> {
                out.append(pad).append('<').append(name).append('>').append(value.numberText)
                out.append("</").append(name).append('>').append(newline)
            }
            value.boolValue != null -> {
                out.append(pad).append('<').append(name).append('>').append(value.boolValue)
                out.append("</").append(name).append('>').append(newline)
            }
            value is JsonObject -> {
                val members = value.members
                val attributes = members.filter { it.key.startsWith(ATTRIBUTE_PREFIX) }
                val text = members[TEXT]?.stringValue
                val children = members.filter { !it.key.startsWith(ATTRIBUTE_PREFIX) && it.key != TEXT }
                if (attributes.isEmpty() && children.isEmpty() && text != null) {
                    out.append(pad).append('<').append(name).append('>').append(escapeText(text))
                    out.append("</").append(name).append('>').append(newline)
                    return
                }
                out.append(pad).append('<').append(name)
                attributes.forEach { (key, child) ->
                    val plain = key.removePrefix(ATTRIBUTE_PREFIX)
                    require(isElementName(plain)) { "属性名「$plain」不能当 XML 名字用" }
                    require(child.arrayValue.isEmpty() && child !is JsonObject) {
                        "属性「$plain」的值是集合，XML 的属性只能放一个值：把它挪成子元素再转"
                    }
                    out.append(' ').append(plain).append("=\"").append(escapeAttribute(child.text())).append('"')
                }
                out.append('>').append(newline)
                // 混合内容里文字要先写：放在孩子后面就成了上一个孩子的 tail，再解析回来
                // 这个元素的自己的文字就没了 —— 那是丢数据，不是"顺序不保留"
                if (text != null) {
                    out.append(if (indent > 0) " ".repeat(indent * (depth + 1)) else "").append(escapeText(text)).append(newline)
                }
                children.forEach { (key, child) ->
                    require(isElementName(key)) { "键「$key」不能当 XML 标签名：请改名后再转" }
                    writeElement(out, key, child, depth + 1, indent)
                }
                out.append(pad).append("</").append(name).append('>').append(newline)
            }
        }
    }

    /**
     * 标量取值。`stringValue` 这些是带自定义 getter 的属性，判完空不能智能转换，
     * 所以一律先落到本地变量上 —— 不用 `!!`，那等于把判空的责任推给运气。
     */
    private fun Json.text(): String {
        stringValue?.let { return it }
        numberText?.let { return it }
        boolValue?.let { return it.toString() }
        if (isNull) return ""
        return JsonRender.render(this)
    }

    private fun escapeText(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttribute(text: String): String = escapeText(text)
        .replace("\"", "&quot;").replace("\n", "&#10;").replace("\t", "&#9;").replace("\r", "&#13;")
}
