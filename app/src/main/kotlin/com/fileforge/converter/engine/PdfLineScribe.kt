package com.fileforge.converter.engine

import com.fileforge.core.pdf.PdfLine
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.max
import kotlin.math.min

/**
 * 一页一页地量：每行的字、字号、用的是哪一种字、左右上下的位置。
 *
 * PDFTextStripper 一行会分几次回调（词与词之间另有 writeWordSeparator），所以这里先攒着，
 * 遇到行尾才结一次账。`tools/pdfprobe` 里那份 Java 走的是同一套规矩 —— 那边能在桌面上拿
 * pdfminer 独立量的字号与位置对答案，这份只是把同一件事在安卓上做一遍。
 */
internal class PdfLineScribe(private val pageNo: Int) : PDFTextStripper() {

    val lines = ArrayList<PdfLine>()

    private val text = StringBuilder()
    private var size = 0f
    private var left = Float.MAX_VALUE
    private var top = Float.MAX_VALUE
    private var height = 0f
    private var right = 0f
    private var heavy = false

    init {
        setSortByPosition(true)
    }

    override fun writeString(chunk: String, positions: MutableList<TextPosition>) {
        if (positions.isEmpty()) return
        text.append(chunk)
        positions.forEach { position ->
            size = max(size, position.fontSizeInPt)
            left = min(left, position.xDirAdj)
            top = min(top, position.yDirAdj)
            height = position.pageHeight
            right = max(right, position.xDirAdj + position.widthDirAdj)
            if (isHeavy(position.font)) heavy = true
        }
    }

    override fun writeWordSeparator() {
        text.append(' ')
    }

    override fun writeLineSeparator() {
        settle()
    }

    override fun endPage(page: PDPage?) {
        settle()
    }

    private fun settle() {
        val value = text.toString().trimEnd()
        if (value.isNotBlank() && size > 0f) {
            lines += PdfLine(value, size, heavy, if (left == Float.MAX_VALUE) 0f else left, top, pageNo, height, right)
        }
        text.setLength(0)
        size = 0f
        left = Float.MAX_VALUE
        top = Float.MAX_VALUE
        height = 0f
        right = 0f
        heavy = false
    }

    /**
     * 重面字按**字体名**认，不按 `/Flags` 的 Bold 位：真文件里宋体被标成粗体的能占到八九成
     * （`python tools/measure_pdf_fonts.py` 量的），照标志位判会把每行短的都排成标题。
     */
    private fun isHeavy(font: PDFont?): Boolean {
        val name = font?.fontDescriptor?.fontName ?: return false
        return HEAVY.containsMatchIn(name)
    }

    private companion object {

        // SimHei / 微软雅黑 / *-Bold / 嵌入子集写作 `xxx.B` 的那些，都是画出来就比正文重的字
        val HEAVY = Regex("(bold|black|heavy|hei|黑|粗|\\.B$|-B($|[0-9.]))", RegexOption.IGNORE_CASE)
    }
}
