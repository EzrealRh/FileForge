package com.fileforge.core.pdf

import kotlin.math.max
import kotlin.math.min

/** 改纸张时内容怎么摆：留白、裁边、或拉伸。 */
enum class PageResizeMode(val label: String) {
    Contain("等比留白"),
    Cover("等比裁边"),
    Stretch("拉伸铺满"),
}

/**
 * 缩放矩阵（在**未旋转**的用户坐标里）与新页框尺寸。
 *
 * 页面带 /Rotate 时，内容坐标仍按未旋转写；显示尺寸要把宽高对调。
 * 所以拉伸模式下两条轴的缩放也要跟着对调，否则旋转页会被拉反。
 */
data class PageResize(
    val scaleX: Float,
    val scaleY: Float,
    val translateX: Float,
    val translateY: Float,
    val boxWidth: Float,
    val boxHeight: Float,
    val displayWidth: Float,
    val displayHeight: Float,
)

object PageResizePlan {

    fun plan(
        sourceWidth: Float,
        sourceHeight: Float,
        sourceLowerLeftX: Float = 0f,
        sourceLowerLeftY: Float = 0f,
        rotation: Int,
        paperWidth: Float,
        paperHeight: Float,
        mode: PageResizeMode,
    ): PageResize {
        require(sourceWidth > 0f && sourceHeight > 0f) { "源页尺寸不合法" }
        require(paperWidth > 0f && paperHeight > 0f) { "纸张尺寸不合法" }
        val turn = ((rotation % 360) + 360) % 360
        val swapped = turn == 90 || turn == 270
        val displayW = if (swapped) sourceHeight else sourceWidth
        val displayH = if (swapped) sourceWidth else sourceHeight
        val landscape = displayW > displayH
        val portraitShort = min(paperWidth, paperHeight)
        val portraitLong = max(paperWidth, paperHeight)
        val targetW = if (landscape) portraitLong else portraitShort
        val targetH = if (landscape) portraitShort else portraitLong

        val sxDisplay = targetW / displayW
        val syDisplay = targetH / displayH
        val uniform = when (mode) {
            PageResizeMode.Contain -> min(sxDisplay, syDisplay)
            PageResizeMode.Cover -> max(sxDisplay, syDisplay)
            PageResizeMode.Stretch -> null
        }
        val sxD = uniform ?: sxDisplay
        val syD = uniform ?: syDisplay
        // 旋转 90/270 时，未旋转的 X 轴显示成视觉的 Y 轴，拉伸的两轴要互换
        val sxU = if (swapped) syD else sxD
        val syU = if (swapped) sxD else syD
        val boxW = if (swapped) targetH else targetW
        val boxH = if (swapped) targetW else targetH
        val tx = (boxW - sourceWidth * sxU) / 2f - sourceLowerLeftX * sxU
        val ty = (boxH - sourceHeight * syU) / 2f - sourceLowerLeftY * syU
        return PageResize(sxU, syU, tx, ty, boxW, boxH, targetW, targetH)
    }
}
