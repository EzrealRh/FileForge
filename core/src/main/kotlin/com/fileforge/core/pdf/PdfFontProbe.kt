package com.fileforge.core.pdf

/**
 * 问字体"能不能显示这段文字"之前，先把不需要字形的字符挑掉。
 *
 * 为什么要有这一份：PDF 里换行、制表符这些**不占字形** —— 排版是把文字切成一行行、按坐标画的，
 * 控制字符根本不会进 `showText`。而"这个字体能不能用"的探针若拿原始整段去问（`font.encode(整段)`），
 * 任何 TTF 都会在 `\n` 或 `\t` 上抛"没有这个字"，于是**一台装了中文字体的手机上也照样挑不出字体**，
 * 整次打印报错。桌面替身是自己直接加载字体文件的，这条探针它一步都没走过。
 *
 * 两条口径写在这里而不是散在引擎里：需要字形的字符集 [glyphChars]，以及"只有这些字符集能不能
 * 交给内置的 Helvetica" [latinOnly] —— 判定与挑选共用同一份口径，才不会一处认为够用、另一处认为不够。
 */
object PdfFontProbe {

    /** 这段文字里**需要字形**的字符：空白与控制字符都排除（它们不进画布）。去重、保序。 */
    fun glyphChars(sample: String): String {
        val seen = LinkedHashSet<Char>()
        sample.forEach { ch -> if (!ch.isWhitespace() && !ch.isISOControl()) seen.add(ch) }
        return seen.joinToString("")
    }

    /** 需要字形的部分全是可打印 ASCII，就能用内置 Helvetica；空白与控制字符不算需求。 */
    fun latinOnly(sample: String): Boolean = glyphChars(sample).all { it.code in 32..126 }

    /**
     * 挑字体的顺序：系统里的候选先试（能用系统字体就不必走内置的那一份），
     * 都不行才用随应用打包的。空路径丢掉，不占一位。
     */
    fun candidateOrder(systemPaths: List<String>, bundled: String?): List<String> =
        systemPaths.map { it.trim() }.filter { it.isNotEmpty() } + listOfNotNull(bundled?.trim()?.takeIf { it.isNotEmpty() })

}
