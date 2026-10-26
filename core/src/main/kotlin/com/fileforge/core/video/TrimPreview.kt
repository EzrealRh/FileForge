package com.fileforge.core.video

import com.fileforge.core.util.TimeInput

/** 「视频截取」面板里先看哪一刻：第一段的起点（微秒）；没写或写反了给 null。 */
object TrimPreview {

    fun firstStartUs(spec: String): Long? =
        TimeInput.parseRanges(spec).firstOrNull()?.first?.let { (it * 1_000_000).toLong() }
}
