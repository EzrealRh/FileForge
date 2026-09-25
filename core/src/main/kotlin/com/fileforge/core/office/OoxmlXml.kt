package com.fileforge.core.office

import com.fileforge.core.data.Xml
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * OOXML 部件的读入这一层：解字节、挡外部实体、交给 DOM。
 *
 * `core/data/Xml` 那份是同类的东西，但它面向"XML ↔ JSON"的映射，这里要的是一棵能自己走的树，
 * 两套需求各自演进比硬并成一层清楚 —— 唯一必须一致的是防御：外部实体一律不取。
 */
internal object OoxmlXml {

    /** 部件必须是 UTF-8：认不出的一律报错，不拿替换字符凑出一份看着正常的文本。 */
    fun text(part: ByteArray): String {
        require(!part.contains(0.toByte())) { "这个部件不是文本，OOXML 要求它是 UTF-8 的 XML" }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = try {
            decoder.decode(ByteBuffer.wrap(part)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("这个部件按 UTF-8 读不出来，它不是合法的 OOXML")
        }
        // 带 BOM 的部件规范上不允许，但 LibreOffice 真会写：不剥掉会连首行一起脏掉
        return decoded.removePrefix("\uFEFF")
    }

    /** 解成 DOM 的根元素；带 DTD 的部件直接拒。 */
    fun root(part: ByteArray): Element {
        val text = text(part)
        Xml.rejectDoctype(text)       // OOXML 从不带 DTD，带了就是别的东西
        val factory = DocumentBuilderFactory.newInstance()
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        ).forEach { (feature, value) -> runCatching { factory.setFeature(feature, value) } }
        factory.isNamespaceAware = false
        val stream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        return factory.newDocumentBuilder().parse(stream).documentElement
    }
}
