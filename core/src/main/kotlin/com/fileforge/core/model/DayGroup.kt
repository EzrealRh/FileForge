package com.fileforge.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 工作台按日期归类时的档名。 */
enum class DayGroup(val label: String) {
    Today("今天"),
    Yesterday("昨天"),
    Week("本周"),
    Earlier("更早"),
    ;

    companion object {

        /**
         * 按"加入工作台那天"归档。周一为一周起点（国内习惯），所以周日看到的"本周"
         * 只有当天。今天、昨天比"本周"更精确，所以优先于本周档 —— 昨天的文件即使
         * 同属本周，也归到昨天。[now] 传当前时刻，测试里可以固定住。
         */
        fun of(addedAt: Long, now: Long): DayGroup {
            val then = localDate(addedAt)
            val today = localDate(now)
            val days = ChronoUnit.DAYS.between(then, today)
            return when {
                days <= 0 -> Today
                days == 1L -> Yesterday
                !then.isBefore(today.minusDays((today.dayOfWeek.value - 1).toLong())) -> Week
                else -> Earlier
            }
        }

        private fun localDate(millis: Long): LocalDate =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
