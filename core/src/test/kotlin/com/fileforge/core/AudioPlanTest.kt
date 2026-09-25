package com.fileforge.core

import com.fileforge.core.audio.AudioPlan
import com.fileforge.core.audio.AudioTarget
import com.fileforge.core.audio.WavHeader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 转音频的判据。这一层的价值不在于它能跑，而在于它在**没有真机的情况下**
 * 就能把"写出空壳却报成功"那一类缺陷判死 —— 视频压缩那两个必现缺陷就是这么漏掉的。
 */
class AudioPlanTest {

    private fun mimes(vararg m: String?) = m.toList()

    @Test
    fun `只有 AAC 能原样进 m4a`() {
        listOf("audio/mp4a-latm", "audio/aac", "audio/mp4a-adts", "AUDIO/AAC ", "audio/aac;profile=2").forEach {
            assertTrue(AudioPlan.canPassthrough(it, AudioTarget.M4a), "$it 该能直通")
        }
        // MP3 看着像"也是现成的压缩音频"，但安卓 muxer 对它的 sample entry 支持不一致
        assertFalse(AudioPlan.canPassthrough("audio/mpeg", AudioTarget.M4a))
        assertFalse(AudioPlan.canPassthrough("audio/flac", AudioTarget.M4a))
        assertFalse(AudioPlan.canPassthrough("audio/mp4a-latm", AudioTarget.Wav), "WAV 是无损 PCM，AAC 必须解码")
        assertFalse(AudioPlan.canPassthrough(null, AudioTarget.M4a))
        assertFalse(AudioPlan.canPassthrough("", AudioTarget.M4a))
    }

    @Test
    fun `挑轨必须返回下标而不是有没有`() {
        // 缺陷①的成因就是"知道有音轨，却按 0 号轨去解" —— 0 号往往是视频轨
        val withVideo = mimes("video/avc", "audio/mpeg")
        assertEquals(1, AudioPlan.pickAudioTrack(withVideo))
        assertEquals(0, AudioPlan.pickAudioTrack(mimes("audio/mp4a-latm")))
        assertEquals(-1, AudioPlan.pickAudioTrack(mimes("video/avc", null)))
        assertEquals(-1, AudioPlan.pickAudioTrack(emptyList()))
        assertTrue(AudioPlan.hasAudioTrack(withVideo))
        assertFalse(AudioPlan.hasAudioTrack(mimes("video/hevc")))
    }

    @Test
    fun `安卓解不开的格式要能提前判出来`() {
        listOf("audio/mpeg", "audio/mp3", "audio/flac", "audio/opus", "audio/vorbis", "audio/raw", "audio/wav")
            .forEach { assertTrue(AudioPlan.decodableMime(it), "$it 应当可解") }
        listOf("audio/x-wma", "audio/ape", "audio/mp4a-latmX", null, "", "video/avc").forEach {
            assertFalse(AudioPlan.decodableMime(it), "$it 不该被判成可解")
        }
    }

    @Test
    fun `目标体积反推码率并且夹在编码器支持面里`() {
        // 4MB / 240 秒 = 133kbps
        assertEquals(133, AudioPlan.bitrateKbps(4_000_000L, 240_000_000L, null))
        // 反推出格子的要夹住：低于 64 没人能听，高于 320 系统 AAC 编码器不一定收
        assertEquals(AudioPlan.MIN_KBPS, AudioPlan.bitrateKbps(100_000L, 600_000_000L, null))
        assertEquals(AudioPlan.MAX_KBPS, AudioPlan.bitrateKbps(50_000_000L, 60_000_000L, null))
        // 时长拿不到就别反推 —— 基于错时长算出的码率比不填更坏，退回跟随源
        assertEquals(128, AudioPlan.bitrateKbps(4_000_000L, 0L, 128))
        assertEquals(AudioPlan.DEFAULT_KBPS, AudioPlan.bitrateKbps(null, 0L, null))
        assertEquals(96, AudioPlan.bitrateKbps(null, 123L, 96))
    }

    @Test
    fun `字节数大于零不算成功，搬过样本才算`() {
        assertFalse(AudioPlan.succeeded(0), "muxer 一 start 就写文件头，空壳也有几 KB")
        assertTrue(AudioPlan.succeeded(1))
    }

    @Test
    fun `转格式保持原名，从视频提声音要标出来`() {
        assertEquals("播客.m4a", AudioPlan.outputName("播客.mp3", AudioTarget.M4a, fromVideo = false))
        assertEquals("演讲_音频.m4a", AudioPlan.outputName("演讲.mp4", AudioTarget.M4a, fromVideo = true))
        assertEquals("demo_音频.wav", AudioPlan.outputName("demo.webm", AudioTarget.Wav, fromVideo = true))
        // 名字里带点的不能被截错：stem 只砍最后一个点
        assertEquals("v1.2.m4a", AudioPlan.outputName("v1.2.flac", AudioTarget.M4a, fromVideo = false))
    }

    @Test
    fun `目标格式按扩展名回得来`() {
        assertEquals(AudioTarget.M4a, AudioTarget.ofExtension("M4A"))
        assertEquals(AudioTarget.Wav, AudioTarget.ofExtension(" wav"))
        assertEquals(null, AudioTarget.ofExtension("mp3"))
    }
}

class WavHeaderTest {

    @Test
    fun `头是 44 字节且各处偏移都对`() {
        // 44100Hz 立体声 16 位、1 秒 = 176400 字节 PCM
        val h = WavHeader.pcm16(176_400L, 44_100, 2)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4, Charsets.ISO_8859_1))
        assertEquals("WAVE", String(h, 8, 4, Charsets.ISO_8859_1))
        assertEquals("fmt ", String(h, 12, 4, Charsets.ISO_8859_1))
        assertEquals("data", String(h, 36, 4, Charsets.ISO_8859_1))
        assertEquals(36 + 176_400, u32(h, 4), "RIFF 块长度不含自身 8 字节")
        assertEquals(16, u32(h, 16), "PCM 的 fmt 块恒为 16")
        assertEquals(1, u16(h, 20), "1 = 无压缩 PCM")
        assertEquals(2, u16(h, 22))
        assertEquals(44_100, u32(h, 24))
        assertEquals(176_400, u32(h, 28), "byteRate = 采样率 × blockAlign")
        assertEquals(4, u16(h, 32), "blockAlign = 声道 × 位深/8")
        assertEquals(16, u16(h, 34))
        assertEquals(176_400, u32(h, 40))
    }

    @Test
    fun `小端序必须逐字节钉住`() {
        val h = WavHeader.pcm16(258L, 44_100, 1)
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x00, 0x00), h.copyOfRange(40, 44), "258 的小端")
        // 44100 = 0xAC44 → 小端是 44 AC 00 00
        assertArrayEquals(byteArrayOf(0x44, 0xAC.toByte(), 0x00, 0x00), h.copyOfRange(24, 28))
    }

    @Test
    fun `单声道和八位也要自洽`() {
        val h = WavHeader.of(11_025L, 11_025, 1, 8)
        assertEquals(1, u16(h, 32))
        assertEquals(11_025, u32(h, 28))
        assertEquals(8, u16(h, 34))
        assertEquals(36 + 11_025, u32(h, 4))
    }

    @Test
    fun `回填长度只动前 44 字节`() {
        val buf = ByteArray(100).also { it[44] = 7 }
        WavHeader.rewrite(buf, 56L, 22_050, 1, 16)
        assertEquals(56, u32(buf, 40))
        assertEquals(7.toByte(), buf[44], "PCM 第一个字节不能被动掉")
    }

    @Test
    fun `非法参数直接拒绝而不是写个坏头`() {
        assertThrows(IllegalArgumentException::class.java) { WavHeader.pcm16(-1L, 44_100, 2) }
        assertThrows(IllegalArgumentException::class.java) { WavHeader.pcm16(100L, 0, 2) }
        assertThrows(IllegalArgumentException::class.java) { WavHeader.pcm16(100L, 44_100, 0) }
        assertThrows(IllegalArgumentException::class.java) { WavHeader.of(100L, 44_100, 2, 12) }
    }

    @Test
    fun `与第三方写手逐字节相同`() {
        // 参考值出处：Python 标准库 wave 写出 1 秒 44100Hz 立体声 16 位静音的真文件，
        // 取前 44 字节。用它当参照是因为 wave 是独立实现 —— 我只跟自己一致是不够的，
        // 得跟"别人能读"一致。复刻：wave.open('r.wav','wb') 设 2 声道 / 2 字节样宽 /
        // 44100 帧率，writeframes(176400 字节)，读回前 44 字节。
        val reference = intArrayOf(
            0x52, 0x49, 0x46, 0x46, 0x34, 0xB1, 0x02, 0x00, 0x57, 0x41, 0x56, 0x45,
            0x66, 0x6D, 0x74, 0x20, 0x10, 0x00, 0x00, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x44, 0xAC, 0x00, 0x00, 0x10, 0xB1, 0x02, 0x00, 0x04, 0x00, 0x10, 0x00,
            0x64, 0x61, 0x74, 0x61, 0x10, 0xB1, 0x02, 0x00,
        ).map { it.toByte() }.toByteArray()
        assertArrayEquals(reference, WavHeader.pcm16(176_400L, 44_100, 2))
    }

    private fun u16(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
