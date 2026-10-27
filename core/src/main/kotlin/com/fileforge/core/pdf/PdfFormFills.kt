package com.fileforge.core.pdf

/** 一个要填的字段。`name` 是 AcroForm 的完全限定名，`value` 原样交给 PDFBox。 */
data class PdfFill(val name: String, val value: String)

/**
 * 表单填写规格：每行一个 `字段名=值`，也接受分号分隔；值里可以再出现 `=`（按第一个等号切）。
 * 复选框的值认 true/yes/on/1/是/勾选，其余按 false。
 */
object PdfFormFills {

    fun parse(spec: String): List<PdfFill> =
        spec.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val at = line.indexOf('=')
            require(at > 0) { "「$line」要写成 字段名=值" }
            val name = line.substring(0, at).trim()
            require(name.isNotEmpty()) { "「$line」的字段名是空的" }
            PdfFill(name, line.substring(at + 1).trim())
        }

    /** 参数面板用：null 表示能提交；否则是给用户看的原因。 */
    fun problem(spec: String): String? {
        if (spec.isBlank()) return "先写要填的字段，每行一个：字段名=值"
        val fills = runCatching { parse(spec) }.getOrElse { return it.message ?: "表单填写格式不对" }
        return if (fills.isEmpty()) "先写要填的字段，每行一个：字段名=值" else null
    }

    fun truthy(value: String): Boolean =
        value.trim().lowercase() in setOf("true", "yes", "on", "1", "是", "勾选", "checked")
}
