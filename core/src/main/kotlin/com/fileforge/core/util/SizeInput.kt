package com.fileforge.core.util

/** 解析用户输入的大小："10"、"10M"、"1.5 MB"、"500k"、"2G"。缺单位按 MB 处理。 */
object SizeInput {

    const val KILO = 1024L
    const val MEGA = 1024L * 1024L
    const val GIGA = 1024L * 1024L * 1024L

    fun parse(text: String): Long? {
        val cleaned = text.trim()
            .replace("，", "")
            .replace(",", "")
            .replace("　", "")
            .lowercase()
        if (cleaned.isEmpty()) return null

        val match = Regex("""^([0-9]*\.?[0-9]+)\s*([kmgt]b?|兆|吉|千字节)?$""").find(cleaned) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        if (value <= 0) return null

        val multiplier = when (match.groupValues[2]) {
            "", "m", "mb", "兆" -> MEGA
            "k", "kb", "千字节" -> KILO
            "g", "gb", "吉" -> GIGA
            "t", "tb" -> GIGA * 1024
            else -> return null
        }
        return (value * multiplier).toLong()
    }

    fun format(bytes: Long): String {
        if (bytes < KILO) return "$bytes B"
        val kb = bytes.toDouble() / KILO
        if (kb < KILO) return trim(kb) + " KB"
        val mb = kb / KILO
        if (mb < KILO) return trim(mb) + " MB"
        return trim(mb / KILO) + " GB"
    }

    private fun trim(value: Double): String {
        val rounded = Math.round(value * 10) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }
}
