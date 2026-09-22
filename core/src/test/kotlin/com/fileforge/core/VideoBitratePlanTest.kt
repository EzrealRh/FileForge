package com.fileforge.core

import com.fileforge.core.util.SizeInput
import com.fileforge.core.video.VideoBitratePlan
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `算出的码率低于编码器下限时不反抬，直接放弃目标体积`() {
        // 4MB / 200 秒：反推只有 160kbps，低于编码器下限。
        // 抬到下限会让成品接近目标的两倍，等于违背用户写下的体积承诺。
        assertNull(VideoBitratePlan.videoBitrateBps(4L * SizeInput.MEGA, 200 * seconds, 128_000))
    }

    @Test
    fun `上限仍然夹住，超长视频不会算出离谱码率`() {
        val bps = VideoBitratePlan.videoBitrateBps(500L * SizeInput.MEGA, 10 * seconds, 0)!!
        assertEquals(VideoBitratePlan.MAX_BPS, bps)
    }
}
