package com.fileforge.core.pdf

/** PDF 压缩的一档：图片最长边 + JPEG 重编质量（0..1）。 */
data class PdfTier(val maxEdge: Int, val quality: Float) {
    val label: String get() = "${maxEdge}px · 质量${(quality * 100).toInt()}"
}

/**
 * PDF 压缩档位。阶梯按「先别把字压糊」排序，从松到紧；给了目标体积就一档不行再往下走一档，
 * 走完还没达标就用最紧的那一档并如实说明——PDF 里的文字、矢量图形压不动，只能承诺图片部分。
 */
object PdfCompressPlan {

    val ladder = listOf(
        PdfTier(1800, 0.72f),
        PdfTier(1400, 0.62f),
        PdfTier(1000, 0.52f),
        PdfTier(700, 0.42f),
    )

    /** level 从 0 开始，对应界面上的「清晰 / 标准 / 紧凑」三档；档位之后的阶梯尾巴留给目标体积迭代。 */
    fun tiersFrom(level: Int): List<PdfTier> {
        val start = level.coerceIn(0, ladder.lastIndex)
        return ladder.subList(start, ladder.size).toList()
    }

    fun tier(level: Int): PdfTier = ladder[level.coerceIn(0, ladder.lastIndex)]
}
