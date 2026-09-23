package com.fileforge.core.update

/**
 * 点分数字版本号。GitHub 的 release 里只有 `v0.4.2` 这种标签，没有 versionCode，
 * 所以比较只能按数字段来：0.10.0 必须大于 0.9.9，不能按字符串比。
 */
class SemanticVersion private constructor(val parts: List<Int>, val raw: String) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        val depth = maxOf(parts.size, other.parts.size)
        for (index in 0 until depth) {
            val left = parts.getOrElse(index) { 0 }
            val right = other.parts.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is SemanticVersion && compareTo(other) == 0

    override fun hashCode(): Int = parts.hashCode()

    override fun toString(): String = raw

    companion object {

        /** 读不出至少两段纯数字就当读不出，交给界面如实显示"版本未知"，不猜。 */
        fun parse(text: String?): SemanticVersion? {
            val cleaned = text?.trim()?.trimStart('v', 'V') ?: return null
            val segments = cleaned.split('.')
            if (segments.size < 2) return null
            val parts = segments.map { segment ->
                if (segment.isEmpty() || !segment.all(Char::isDigit)) return null
                segment.toIntOrNull() ?: return null
            }
            return SemanticVersion(parts, cleaned)
        }
    }
}
