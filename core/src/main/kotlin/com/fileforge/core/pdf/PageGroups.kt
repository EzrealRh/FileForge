package com.fileforge.core.pdf

/** 页数均分：拆成 N 份用它。前面的份多一页，余数摊给最靠前的那份，页数不够时份数自动降到页数。 */
object PageGroups {

    fun evenSized(total: Int, parts: Int): List<List<Int>> {
        require(total > 0) { "没有页面可分割" }
        require(parts > 0) { "份数必须大于 0" }

        val count = minOf(parts, total)
        val base = total / count
        val extra = total % count
        val groups = ArrayList<List<Int>>(count)
        var cursor = 0
        repeat(count) { index ->
            val size = base + if (index < extra) 1 else 0
            groups += (cursor until cursor + size).toList()
            cursor += size
        }
        return groups
    }
}
