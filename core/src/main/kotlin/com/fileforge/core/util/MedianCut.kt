package com.fileforge.core.util

/**
 * 中位切分量化：每次挑出通道跨度最大的箱子，沿它自己的最长轴在中位数处切成两半。
 * 比按位截断出来的调色板明显更贴原图，代价是多一遍排序。
 */
object MedianCut {

    fun build(colors: IntArray, targetColors: Int): IntArray {
        if (colors.isEmpty()) return IntArray(0)
        val target = targetColors.coerceIn(2, 256)

        val distinct = colors.distinct()
        if (distinct.size <= target) return distinct.toIntArray()

        var boxes = listOf(Box(distinct))
        while (boxes.size < target) {
            val widest = boxes.filter { it.pixelCount >= 2 && it.range > 0 }.maxByOrNull { it.range } ?: break
            boxes = boxes.filterNot { it === widest } + widest.split()
        }
        return IntArray(boxes.size) { boxes[it].average() }
    }

    private class Box(val pixels: List<Int>) {

        val pixelCount: Int = pixels.size
        val range: Int
        private val channel: Int

        init {
            var widest = 0
            var widestChannel = 0
            for (candidate in 0 until 3) {
                var min = 255
                var max = 0
                for (pixel in pixels) {
                    val value = channelOf(pixel, candidate)
                    if (value < min) min = value
                    if (value > max) max = value
                }
                if (max - min > widest) {
                    widest = max - min
                    widestChannel = candidate
                }
            }
            range = widest
            channel = widestChannel
        }

        fun split(): List<Box> {
            val sorted = pixels.sortedBy { channelOf(it, channel) }
            val middle = sorted.size / 2
            return listOf(Box(sorted.subList(0, middle)), Box(sorted.subList(middle, sorted.size)))
        }

        fun average(): Int {
            if (pixels.isEmpty()) return 0
            var red = 0L
            var green = 0L
            var blue = 0L
            for (pixel in pixels) {
                red += channelOf(pixel, 0)
                green += channelOf(pixel, 1)
                blue += channelOf(pixel, 2)
            }
            val count = pixels.size
            return (((red / count).toInt()) shl 16) or (((green / count).toInt()) shl 8) or (blue / count).toInt()
        }
    }

    private fun channelOf(color: Int, index: Int): Int = (color shr (16 - index * 8)) and 0xFF
}
