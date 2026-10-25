package com.fileforge.core.image

/** 多行水印的横向对齐：单处时跟位置走，平铺时一律居中。 */
enum class WatermarkAlign { LEFT, CENTER, RIGHT }

/** 一行水印：文本、量出来的宽度、在整块里离左边的距离。 */
data class WatermarkLine(val text: String, val width: Float, val left: Float)

/** 量好宽度的一整块；纵向步长由调用方按自己的字体度量算（core 不碰字体 API）。 */
data class WatermarkBlock(val lines: List<WatermarkLine>, val width: Float) {

    val isEmpty: Boolean get() = lines.isEmpty()

    fun height(lineStep: Float, lineHeight: Float): Float =
        if (lines.isEmpty()) 0f else (lines.size - 1) * lineStep + lineHeight
}

object WatermarkText {

    /** 行距相对单行字高的倍数；多行压得太近会糊成一块。 */
    const val LINE_SPACING = 1.2f

    /**
     * 只在用户真的换了行的地方断；CRLF / CR 都算换行，行内首尾空白去掉，整段首尾的空行丢掉。
     * 中间的空行保留 —— 那是用户用来拉开行距的。
     */
    fun split(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()
        return normalized.split('\n').map { it.trim() }
    }

    fun layout(text: String, measure: (String) -> Float, align: WatermarkAlign): WatermarkBlock {
        val lines = split(text).map { line ->
            WatermarkLine(line, measure(line).coerceAtLeast(0f), 0f)
        }
        val blockWidth = lines.maxOfOrNull { it.width } ?: 0f
        val placed = lines.map { line ->
            val left = when (align) {
                WatermarkAlign.LEFT -> 0f
                WatermarkAlign.CENTER -> (blockWidth - line.width) / 2f
                WatermarkAlign.RIGHT -> blockWidth - line.width
            }
            line.copy(left = left)
        }
        return WatermarkBlock(placed, blockWidth)
    }
}
