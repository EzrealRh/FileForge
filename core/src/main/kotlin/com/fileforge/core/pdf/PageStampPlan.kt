package com.fileforge.core.pdf

/** 页码/水印落在页面的哪个位置（按**视觉方向**算，旋转页也按你看到的方向）。 */
enum class StampSpot(val label: String, val align: Horizontal, val side: Vertical) {
    BottomCenter("底部中间", Horizontal.CENTER, Vertical.BOTTOM),
    BottomRight("右下角", Horizontal.RIGHT, Vertical.BOTTOM),
    BottomLeft("左下角", Horizontal.LEFT, Vertical.BOTTOM),
    TopCenter("顶部中间", Horizontal.CENTER, Vertical.TOP),
}

enum class Horizontal { LEFT, CENTER, RIGHT }

enum class Vertical { TOP, BOTTOM }

/** 起笔锚点：x 按 align 解释（左端/中心/右端），y 是文字基线。 */
data class Anchor(val x: Float, val y: Float, val align: Horizontal)

/**
 * 页码。PDF 内容流的坐标原点在**左下角**，所以底部留白是直接给 y、顶部要拿页高去减。
 */
object PageNumberPlan {

    const val STYLE_PLAIN = 0
    const val STYLE_CJK = 1
    const val STYLE_TOTAL = 2

    val styles = listOf("1", "第 1 页", "1 / 12")

    /** 页码跟着物理页走：偏移只改起始数字，不让中间页跟着重排。 */
    fun numberFor(pageIndex: Int, firstNumber: Int): Int = firstNumber + pageIndex

    fun text(number: Int, total: Int, style: Int): String = when (style) {
        STYLE_CJK -> "第 $number 页"
        STYLE_TOTAL -> "$number / $total"
        else -> number.toString()
    }

    fun anchor(
        width: Float,
        height: Float,
        spot: StampSpot,
        margin: Float,
        fontSize: Float,
    ): Anchor {
        val safeWidth = width.coerceAtLeast(1f)
        val x = when (spot.align) {
            Horizontal.LEFT -> margin
            Horizontal.CENTER -> safeWidth / 2f
            Horizontal.RIGHT -> safeWidth - margin
        }
        // 底部要把字形的高度余量留出来，否则下缘会被裁掉一半
        val y = if (spot.side == Vertical.BOTTOM) margin + fontSize * 0.25f else height - margin - fontSize
        return Anchor(x, y.coerceAtLeast(0f), spot.align)
    }
}

/**
 * 把"你看到的页面方向"换算成内容流需要的变换。
 *
 * 页面带 /Rotate 时，内容流仍然写在**未旋转**的用户空间里，查看器再整体转过去。
 * 所以想让它显示在视觉上的右下角，得先平移再反向旋转，把坐标系掰成视觉方向。
 */
data class StampFrame(
    val translateX: Float,
    val translateY: Float,
    val rotateDegrees: Float,
    val width: Float,
    val height: Float,
) {
    companion object {
        /**
         * rotation 只接受 90 的整数倍；返回的 width/height 是旋转后你看到的尺寸。
         *
         * 推导（/Rotate=90 表示查看器把内容顺时针转 90°）：内容点 (u,v) 显示在 (v, boxWidth-u)，
         * 反过来"视觉点 (x,y)"就是 u = boxWidth-y、v = x，写成 CTM 即先平移 (boxWidth, 0) 再转 +90°。
         * 270° 同理：显示在 (boxHeight-v, u)，视觉点回推 u = y、v = boxHeight-x → 平移 (0, boxHeight) 转 -90°。
         */
        fun forRotation(boxWidth: Float, boxHeight: Float, rotation: Int): StampFrame {
            val turn = ((rotation % 360) + 360) % 360
            return when (turn) {
                90 -> StampFrame(boxWidth, 0f, 90f, boxHeight, boxWidth)
                180 -> StampFrame(boxWidth, boxHeight, 180f, boxWidth, boxHeight)
                270 -> StampFrame(0f, boxHeight, -90f, boxHeight, boxWidth)
                else -> StampFrame(0f, 0f, 0f, boxWidth, boxHeight)
            }
        }
    }
}

/** 水印的排布：单块居中，或按行列平铺。 */
object WatermarkPlan {

    /** 平铺格子的中心点（视觉坐标，原点在左下）；行列各自 1..12。 */
    fun tiles(width: Float, height: Float, columns: Int, rows: Int): List<Pair<Float, Float>> {
        val cols = columns.coerceIn(1, 12)
        val lines = rows.coerceIn(1, 12)
        val safeWidth = width.coerceAtLeast(1f)
        val safeHeight = height.coerceAtLeast(1f)
        return (0 until lines).flatMap { row ->
            (0 until cols).map { column ->
                safeWidth * (column + 0.5f) / cols to safeHeight * (row + 0.5f) / lines
            }
        }
    }

    /** 单块居中时的中心点。 */
    fun center(width: Float, height: Float): Pair<Float, Float> =
        width.coerceAtLeast(1f) / 2f to height.coerceAtLeast(1f) / 2f

    /** 平铺时每格能占的宽度：别让相邻两行的字叠在一起。 */
    fun cellWidth(pageWidth: Float, columns: Int, gapRatio: Float = 0.9f): Float =
        pageWidth.coerceAtLeast(1f) / columns.coerceIn(1, 12) * gapRatio.coerceIn(0.1f, 1f)
}

/**
 * 没有字体度量时的大小估算。中日韩字形基本是等宽方块，拉丁字母窄得多，
 * 所以按码位分两类给系数：字号 = 目标宽度 / 系数之和。
 */
object TextFit {

    fun widthFactor(char: Char): Float = if (char.code >= 0x2E80) 1f else 0.55f

    fun estimateWidth(text: String, fontSize: Float): Float =
        text.sumOf { widthFactor(it).toDouble() }.toFloat() * fontSize

    /** 让文字宽度不超过 maxWidth 的最大字号，夹在 [minSize, maxSize] 之间。 */
    fun largestFontSize(
        text: String,
        maxWidth: Float,
        minSize: Float = 6f,
        maxSize: Float = 160f,
    ): Float {
        val units = text.sumOf { widthFactor(it).toDouble() }.toFloat()
        if (units <= 0f || maxWidth <= 0f) return minSize
        return (maxWidth / units).coerceIn(minSize, maxSize)
    }
}
