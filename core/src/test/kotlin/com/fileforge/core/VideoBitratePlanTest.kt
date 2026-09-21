package com.fileforge.core

import com.fileforge.core.util.SizeInput
import com.fileforge.core.video.VideoBitratePlan
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VideoBitratePlanTest {

    private val seconds = 1_000_000L

    @Test
    fun `十分钟 10MB 目标给出可用的码率`() {
        val bps = VideoBitratePlan.videoBitrateBps(10L * SizeInput.MEGA, 30 * seconds, 96_000)!!
        assertTrue(bps in 2_400_000..2_600_000, "实际 $bps bps")
    }

    @Test
    fun `时长拿不到就不猜，直接返回空`() {
        assertNull(VideoBitratePlan.videoBitrateBps(10L * SizeInput.MEGA, 0L, 96_000))
        assertNull(VideoBitratePlan.videoBitrateBps(10L * SizeInput.MEGA, -1L, 96_000))
        assertNull(VideoBitratePlan.videoBitrateBps(0L, 30 * seconds, 96_000))
    }

    @Test
    fun `目标体积比音轨本身还小时不硬压`() {
        // 60 秒 192kbps 音轨就要 1.44MB，再乘安全系数后没有空间给画面
        assertNull(VideoBitratePlan.videoBitrateBps(1_000_000L, 60 * seconds, 192_000))
    }

    @Test
    fun `算出的码率夹在编码器能接受的区间里`() {
        assertTrue(VideoBitratePlan.videoBitrateBps(2L * SizeInput.MEGA, 600 * seconds, 1_000)!! >= VideoBitratePlan.MIN_BPS)
        assertTrue(VideoBitratePlan.videoBitrateBps(500L * SizeInput.MEGA, 10 * seconds, 96_000)!! <= VideoBitratePlan.MAX_BPS)
    }

    @Test
    fun `按码率反推体积和给的目标基本吻合`() {
        val bps = VideoBitratePlan.videoBitrateBps(10L * SizeInput.MEGA, 30 * seconds, 96_000)!!
        val estimated = VideoBitratePlan.estimateBytes(bps, 96_000, 30 * seconds)
        assertTrue(estimated <= 10L * SizeInput.MEGA, "反推 $estimated 超过目标")
        assertTrue(estimated >= 8L * SizeInput.MEGA, "反推 $estimated 浪费太多空间")
    }
}
