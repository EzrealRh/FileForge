package com.fileforge.core.image

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 图上的一块矩形：左与上含、右与下不含，单位是**源图的像素**。 */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** 镜像的四种给法。 */
enum class Flip { None, Horizontal, Vertical, Both }

/**
 * 改一张图的**方向与边界**：旋转、镜像、裁剪 —— 所有尺寸与落点在这里算完，画的那一侧只管执行。
 *
 * 为什么要在 `:core` 里算：安卓那边是 `Bitmap` + `Matrix`，桌面判据那边是 Pillow，两边各写一套
 * "转 90° 后是多大"迟早分家（浮点 `cos(90°)` 不是恰好 0，一律按三角公式进一会给 100×50 转出一个
 * 51 高的画布）。三条出路共用这一份判定，产物说明里那句"出来是多少×多少"也就不会与纸上的对不上。
 *
 * 角度口径：**顺时针为正**（与 `Matrix.postRotate` 同一条，也与人嘴上说的"往右转 90 度"同一条）。
 * 参照实现 Pillow 的 `rotate()` 是逆时针为正，拿它当裁判时要先把符号取反。
 */
object ImageGeometry {

    /** 判"是不是 90 的整数倍"的容差：输入是用户填的数，真误差只来自浮点本身。 */
    private const val ANGLE_TOLERANCE = 0.0001f

    /** 一条边最多多少像素：超过这个数不是照片就是写错了，别让乘法把内存吃穿。 */
    const val MAX_EDGE = 12_000

    /** 一次改图的成品。 */
    data class Plan(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val degrees: Float,
        val flip: Flip,
        val crop: PixelRect?,
        val outWidth: Int,
        val outHeight: Int,
        /** 转的是 90 的整数倍：像素只是搬家，一次插值都不需要。 */
        val quarterTurn: Boolean,
        val notes: List<String>,
    )

    /** 一条裁边写法解出来的块：矩形 + 要交代的话。 */
    data class CropOf(val rect: PixelRect, val notes: List<String>)

    /** 收进 [0, 360)：负角按"往左转了多少"补成整圈，-90 与 270 是同一个方向。 */
    fun normalize(degrees: Float): Float {
        val turned = degrees % 360f
        return if (turned < 0f) turned + 360f else turned
    }

    /** 是 90 的整数倍就给出"顺时针第几格"（0..3），否则 null。 */
    fun quarterTurn(degrees: Float): Int? {
        val norm = normalize(degrees)
        val steps = (norm / 90f).roundToInt()
        if (abs(steps * 90f - norm) > ANGLE_TOLERANCE) return null
        return ((steps % 4) + 4) % 4
    }

    /**
     * 转 [degrees] 度之后**装得下整张图**的最小画布。
     *
     * 90 的整数倍直接换边或原样：这时候按三角公式算会得到 `100 * cos(90°) = 6.1e-15` 这种数，
     * 一律进一就多出一行一列的空边 —— "转 90° 后边上多一条白边"就是这么来的。
     */
    fun boundingBox(width: Int, height: Int, degrees: Float): Pair<Int, Int> {
        quarterTurn(degrees)?.let { return if (it % 2 == 0) width to height else height to width }
        val rad = Math.toRadians(normalize(degrees).toDouble())
        val cos = abs(cos(rad))
        val sin = abs(sin(rad))
        return tighten(width * cos + height * sin) to tighten(width * sin + height * cos)
    }

    /** 离整数只有一个浮点余量时按那个整数算，其余进一（宁可多一列空边也不切掉角上的内容）。 */
    private fun tighten(value: Double): Int =
        if (abs(value - value.roundToInt()) <= 1e-6) value.roundToInt() else ceil(value).toInt()

    /**
     * 裁哪一块：`40,20,300,200` 是"从 (40,20) 起 300×200 个像素"，
     * `10%,5%,80%,90%` 是同一条意思按**源图对应那条边**给的百分比。
     *
     * 越界的边往里收并说明收了多少（拖框常超一两个像素，报错不如讲清楚）；
     * 起点为负、宽高不到一个像素、四段基准不一致是写错了，直说而不是悄悄夹进去。
     */
    fun cropRect(spec: String?, width: Int, height: Int): CropOf? {
        val text = spec?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = text.split(",")
        require(parts.size == 4) { "裁边要四段「左,上,宽,高」，你给的是「$text」" }
        val marked = parts.map { it.trim().endsWith("%") }
        require(marked.all { it } || marked.none { it }) { "四段要么都带 % 要么都不带，混着写没有共同基准" }
        val bases = listOf(width, height, width, height)
        val numbers = parts.mapIndexed { index, piece ->
            val trimmed = piece.trim()
            val raw = trimmed.removeSuffix("%").trim().toFloatOrNull()
                ?: throw IllegalArgumentException("「$piece」不是数，裁边要写「左,上,宽,高」（也可以都带 %）")
            if (marked[index]) (raw / 100f * bases[index]).roundToInt() else raw.roundToInt()
        }
        return rectOf(numbers[0], numbers[1], numbers[2], numbers[3], width, height)
    }

    /** 把「左,上,宽,高」落到源图里：夹边、报数、拒绝空块。 */
    private fun rectOf(left: Int, top: Int, cropWidth: Int, cropHeight: Int, width: Int, height: Int): CropOf {
        require(left >= 0 && top >= 0) { "起点不能是负数（$left,$top 已经在图外了）" }
        require(cropWidth >= 1 && cropHeight >= 1) { "裁出来的宽和高至少各要 1 个像素" }
        if (left >= width || top >= height) throw IllegalArgumentException("这块已经在图外了（图只有 ${width}×$height）")
        val notes = ArrayList<String>()
        var right = left + cropWidth
        var bottom = top + cropHeight
        if (right > width) {
            notes += "右边超出 ${right - width} 个像素，收到图的边上"
            right = width
        }
        if (bottom > height) {
            notes += "下边超出 ${bottom - height} 个像素，收到图的边上"
            bottom = height
        }
        return CropOf(PixelRect(left, top, right, bottom), notes)
    }

    /**
     * 按宽高比（`1:1`、`4:3`、`16:9`…）裁：**居中**取放得下的最大那块。
     *
     * 两边各自向下取整，所以得到的块一定还在图里（往上取整偶尔顶出一个像素，就得反过来夹边并报一条
     * 用户没做错的"超了"）；代价是比例与整数比差不到一个像素。
     */
    fun aspectRect(spec: String?, width: Int, height: Int): CropOf? {
        val text = spec?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = text.split(":")
        require(parts.size == 2) { "宽高比要写成「宽:高」，比如 1:1 或 16:9，你给的是「$text」" }
        val ratioWidth = parts[0].trim().toIntOrNull()
        val ratioHeight = parts[1].trim().toIntOrNull()
        require(ratioWidth != null && ratioHeight != null && ratioWidth > 0 && ratioHeight > 0) {
            "宽高比两边都要是大于 0 的整数，「$text」不行"
        }
        require(ratioWidth <= MAX_EDGE && ratioHeight <= MAX_EDGE) { "宽高比这两数太大了" }
        val scale = min(width / ratioWidth.toDouble(), height / ratioHeight.toDouble())
        val cropWidth = (ratioWidth * scale).toInt()
        val cropHeight = (ratioHeight * scale).toInt()
        require(cropWidth >= 1 && cropHeight >= 1) {
            "这张 ${width}×$height 太小，按 $text 取不出整块（只能得到 ${cropWidth}×$cropHeight）"
        }
        val left = (width - cropWidth) / 2
        val top = (height - cropHeight) / 2
        return CropOf(
            PixelRect(left, top, left + cropWidth, top + cropHeight),
            listOf("按 $text 居中取最大的一块（${cropWidth}×$cropHeight）"),
        )
    }

    /**
     * 三条改图出路共用的那一步：**先裁、再镜像、最后旋转**。
     *
     * 顺序钉死在这一处是有理由的：用户写 `40,20,300,200` 时看着的是**文件里的像素**，
     * 转完再裁就得先心算一个新坐标；先裁后转，出来的尺寸是裁剪块的边长经旋转推出来的，
     * 说明里那句"出来是 200×300"才与纸上一致。两个裁剪给法同时出现时按显式那块，并说出来。
     */
    fun plan(
        width: Int,
        height: Int,
        degrees: Float = 0f,
        flip: Flip = Flip.None,
        cropSpec: String? = null,
        aspectSpec: String? = null,
    ): Plan {
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE) {
            "这张图 ${width}×$height，超出能改的范围（一条边最多 $MAX_EDGE 个像素）"
        }
        val explicit = cropRect(cropSpec, width, height)
        val aspect = aspectRect(aspectSpec, width, height)
        val chosen = explicit ?: aspect
        val notes = ArrayList<String>()
        if (explicit != null && aspect != null) notes += "裁边和宽高比都给了，按「${explicit.rect.width}×${explicit.rect.height}」那块裁，宽高比没用上"
        notes += (chosen?.notes ?: emptyList())
        if (chosen != null) notes += "从原图 (${chosen.rect.left},${chosen.rect.top}) 起裁 ${chosen.rect.width}×${chosen.rect.height}"
        val baseWidth = chosen?.rect?.width ?: width
        val baseHeight = chosen?.rect?.height ?: height
        val turned = normalize(degrees)
        if (flip != Flip.None) {
            notes += when (flip) {
                Flip.Horizontal -> "左右镜像"
                Flip.Vertical -> "上下镜像"
                else -> "左右再上下镜像"
            }
        }
        when (quarterTurn(turned)) {
            0 -> if (turned != 0f) notes += "转的是整圈，方向没变"
            1 -> notes += "顺时针转 90°"
            2 -> notes += "转 180°"
            3 -> notes += "逆时针转 90°"
            else -> notes += "转 ${formatDegrees(turned)}°（不是 90 的整倍数，像素要重采样，画布扩到装得下整张图才不切角）"
        }
        val box = boundingBox(baseWidth, baseHeight, turned)
        notes += "出来是 ${box.first}×${box.second}"
        return Plan(width, height, turned, flip, chosen?.rect, box.first, box.second, quarterTurn(turned) != null, notes)
    }

    /** 30.0 说成「30」、30.5 说成「30.5」：给用户看的那句话里不该有 `.0`，也不受区域小数点影响。 */
    fun formatDegrees(degrees: Float): String {
        val tenth = (degrees * 10f).roundToInt()
        return if (tenth % 10 == 0) (tenth / 10).toString() else "${tenth / 10}.${abs(tenth % 10)}"
    }

    /**
     * 输出容器没有透明通道（JPG）时，透明处怎么落：安卓会把透明像素编成**黑**，
     * 而主流做法是填白 —— 这句话由判层出，应用的说明与桌面替身用的是同一串。
     */
    fun transparentFallback(opaqueFormat: Boolean): String? =
        if (opaqueFormat) "JPG 不留透明，空出来的地方填白" else null
}
