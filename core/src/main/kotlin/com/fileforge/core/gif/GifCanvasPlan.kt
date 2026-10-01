package com.fileforge.core.gif

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 多图合成 GIF 时的画布尺寸。
 *
 * GIF 每帧必须同尺寸，所以画布取所有图的**外接矩形**（横竖各自取最大值），
 * 较小的图等比缩放后居中，不裁切。再按「最长边」和「帧数 x 像素」的内存上限往下收。
 */
object GifCanvasPlan {

    fun canvas(
        widths: List<Int>,
        heights: List<Int>,
        maxEdge: Int = 0,
        pixelBudget: Int,
        minEdge: Int = 32,
    ): Pair<Int, Int> {
        require(widths.size == heights.size) { "宽高数量对不上" }
        require(widths.isNotEmpty()) { "至少一张图才能合成 GIF" }
        val width = widths.max()
        val height = heights.max()
        val longest = maxOf(width, height)

        var scale = 1.0
        if (maxEdge in 1 until longest) scale = min(scale, maxEdge.toDouble() / longest)
        val pixels = width.toLong() * height * widths.size
        if (pixels > pixelBudget) scale = min(scale, sqrt(pixelBudget.toDouble() / pixels))

        // minEdge 是给"缩得太狠"兜底的最小画布；源图本身就比它小的，保持原尺寸，
        // 不能反过来放大（这里说好只缩不放，放大会把 16×8 的源硬扯成 32×32 的画布）
        return (width * scale).roundToInt().coerceAtLeast(minEdge).coerceAtMost(width) to
            (height * scale).roundToInt().coerceAtLeast(minEdge).coerceAtMost(height)
    }

    /** 单帧尺寸 x 帧数是否还在内存上限内；超了调用方要如实报错，别硬解。 */
    fun fits(width: Int, height: Int, frameCount: Int, pixelBudget: Int): Boolean =
        width.toLong() * height * frameCount.toLong() <= pixelBudget
}
