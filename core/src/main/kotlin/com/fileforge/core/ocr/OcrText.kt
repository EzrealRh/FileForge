package com.fileforge.core.ocr

/**
 * OCR 结果的收尾：统一换行、削掉行尾空白、把连续空行压成一个，页与页之间留一条明确的页界。
 *
 * 不合并行、不改标点 —— 认出来是什么就是什么；原文的断行属于版面，按语义硬接会把两句粘成一句。
 */
object OcrText {

    const val EMPTY_PAGE = "（这一页没认出文字）"

    fun normalize(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n')
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** 单页正文：空页不吞掉，明确写出来；页界带上总页数，用户能一眼看出漏没漏。 */
    fun page(number: Int, raw: String, total: Int): String {
        val body = normalize(raw).ifBlank { EMPTY_PAGE }
        return "===== 第 $number 页 / 共 $total 页 =====\n$body"
    }

    fun join(pages: List<String>): String =
        if (pages.isEmpty()) "" else pages.joinToString("\n\n") + "\n"

    /** 字数按"非空白字符"算，页界标记不参与。 */
    fun bodyChars(raw: String): Int = normalize(raw).count { !it.isWhitespace() }
}
