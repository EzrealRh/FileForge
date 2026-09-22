package com.fileforge.core.video

/**
 * 目标体积 → 视频码率。先按总时长算出可用比特数，扣掉音轨预算和容器开销，
 * 再摊到每秒。算不出来（时长未知、或目标比音轨本身还小）就返回 null，
 * 让上层回落到固定码率并如实说明，而不是悄悄给一个错的压法。
 */
object VideoBitratePlan {

    const val MIN_BPS = 200_000
    const val MAX_BPS = 40_000_000

    /** 容器、moov/索引和帧头都要额外占地方，留一点余量才不会压完超标。 */
    const val SAFETY_RATIO = 0.94

    fun videoBitrateBps(
        targetBytes: Long,
        durationUs: Long,
        audioBitrateBps: Int,
        minBps: Int = MIN_BPS,
        maxBps: Int = MAX_BPS,
    ): Int? {
        if (targetBytes <= 0 || durationUs <= 0) return null
        val totalBits = targetBytes * 8
        val audioBits = audioBitrateBps.toLong() * durationUs / 1_000_000L
        val usable = (totalBits * SAFETY_RATIO).toLong() - audioBits
        if (usable <= 0) return null
        val bps = usable * 1_000_000L / durationUs
        // 低于编码器下限就没办法了：抬上去会直接违背用户给的体积承诺
        if (bps < minBps) return null
        return bps.coerceAtMost(maxBps.toLong()).toInt()
    }
}
