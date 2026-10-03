package com.fileforge.core.util

/**
 * 时间输入：截取类操作（视频/音频）共用的解析。
 *
 * 「90」是秒，「1:30」是一分半，「1:02:03」带小时，小数照算（「1:02.5」= 62.5 秒）。
 * 读不出数字、有负数的输入返回 null 或被丢弃，让调用方说"格式不对"。
 */
object TimeInput {

    /** 「90」「1:30」「1:02:03」→ 秒（小数）。空串、负数、非数字给 null。 */
    fun parseSeconds(text: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        var total = 0.0
        for (part in trimmed.split(':')) {
            val value = part.trim().toDoubleOrNull() ?: return null
            if (value < 0) return null
            total = total * 60 + value
        }
        return total
    }

    /**
     * 「5-12,30-41」多段起止 → 秒区间列表；止留空（如「5-」）表示到片尾，end 为 null。
     * 段给反了（止 ≤ 起）直接丢掉；完全读不出段的给空表。
     */
    fun parseRanges(text: String): List<Pair<Double, Double?>> =
        text.split(',', '，').mapNotNull { part ->
            val bits = part.trim().split('-')
            val start = parseSeconds(bits.getOrNull(0) ?: return@mapNotNull null) ?: return@mapNotNull null
            val endText = bits.getOrNull(1)?.trim()
            val openEnd = endText.isNullOrEmpty()
            val end = if (openEnd) null else parseSeconds(endText) ?: return@mapNotNull null
            if (end != null && end <= start) return@mapNotNull null
            start to end
        }
}
