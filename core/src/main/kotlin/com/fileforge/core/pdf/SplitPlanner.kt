package com.fileforge.core.pdf

/**
 * 按目标体积分组页面。
 *
 * measure 要把这些页真的存成一份 PDF 再量字节数，所以调用代价高；这里用
 * 指数扩张 + 二分定位每组能装到第几页，把每组试探次数从 O(页数) 压到 O(log 页数)。
 * 尾部不足目标体积的那一份会单独成文件，这是按体积分割的固有行为。
 */
class SplitPlanner(
    private val targetBytes: Long,
    private val measure: (List<Int>) -> Long,
) {

    fun plan(totalPages: Int): List<List<Int>> {
        require(totalPages > 0) { "没有页面可分割" }
        require(targetBytes > 0) { "目标体积必须大于 0" }

        val groups = ArrayList<List<Int>>()
        var cursor = 0
        while (cursor < totalPages) {
            if (measure(listOf(cursor)) > targetBytes) {
                groups += listOf(cursor)
                cursor++
                continue
            }

            var fitEnd = cursor
            var overEnd = -1
            var probe = 1
            while (true) {
                val candidate = minOf(cursor + probe, totalPages - 1)
                if (candidate <= fitEnd) break
                if (measure((cursor..candidate).toList()) <= targetBytes) {
                    fitEnd = candidate
                    if (candidate == totalPages - 1) break
                    probe *= 2
                } else {
                    overEnd = candidate
                    break
                }
            }

            if (overEnd > 0) {
                var low = fitEnd + 1
                var high = overEnd - 1
                while (low <= high) {
                    val mid = (low + high) / 2
                    if (measure((cursor..mid).toList()) <= targetBytes) {
                        fitEnd = mid
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
            }

            groups += (cursor..fitEnd).toList()
            cursor = fitEnd + 1
        }
        return groups
    }
}
