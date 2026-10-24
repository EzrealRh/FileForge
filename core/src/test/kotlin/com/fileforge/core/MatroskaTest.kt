package com.fileforge.core

import com.fileforge.core.video.Matroska
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatroskaTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/video/$name")) { "缺夹具 $name" }.readBytes()

    @Test
    fun `MKV 里的 SRT 字幕轨按 Block 时间读出两条`() {
        val sub = Matroska.readSubtitles(fixture("withsubs.mkv"))
        assertTrue(sub != null)
        assertEquals("S_TEXT/UTF8", sub!!.codec)
        assertEquals(2, sub.cues.size)
        // 封装里就是 0.523 / 2.023（ffmpeg 封装字幕时带了 23ms 的起点偏移），不是源 SRT 的 0.5
        assertEquals(523L, sub.cues[0].startMs)
        assertEquals(2_023L, sub.cues[1].startMs)
        assertEquals("第一句字幕", sub.cues[0].text)
        assertEquals("第二句字幕", sub.cues[1].text)
        // BlockDuration 给了时长：1.0s 与 0.8s，不按下一句硬补
        assertEquals(1_523L, sub.cues[0].endMs)
        assertEquals(2_823L, sub.cues[1].endMs)
    }

    @Test
    fun `没有字幕轨的 MKV 返回 null`() {
        assertNull(Matroska.readSubtitles(fixture("nosubs.mkv")))
    }

    @Test
    fun `ASS 在 Matroska 里的九段正文与十段 Dialogue 都认`() {
        assertEquals("第一句", Matroska.decodeAssPayload("12,0,Default,,0,0,0,,第一句"))
        assertEquals("你好, 世界", Matroska.decodeAssPayload("1,0:00:01.00,0:00:02.00,Default,,0,0,0,,你好, 世界"))
        assertEquals("两行\n第二行", Matroska.decodeAssPayload("1,0,Default,,0,0,0,,两行\\N第二行"))
    }

    @Test
    fun `ASS 的覆盖标记不进正文`() {
        assertEquals("红字", Matroska.decodeAssPayload("1,0,Default,,0,0,0,,{\\c&H0000FF&}红字{\\r}"))
    }

    @Test
    fun `截断的 MKV 不抛异常`() {
        val bytes = fixture("withsubs.mkv")
        assertNull(Matroska.readSubtitles(bytes.copyOfRange(0, 60)))
    }
}
