package com.fileforge.core

import com.fileforge.core.audio.FlacEncoder
import com.fileforge.core.audio.Pcm16
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.sin

/** FLAC 编码器的结构判据；真正的无损由 tools/verify_flac.py 用 ffmpeg 解码后逐字节比。 */
class FlacEncoderTest {

    @Test
    fun `编码出来的 FLAC 有流头、帧数与源一致`() {
        val frames = 10_000
        val samples = ShortArray(frames * 2) { index ->
            (sin(index * 0.05) * 12_000).toInt().toShort()
        }
        val pcm = Pcm16(48_000, 2, samples)
        val dir = File("build/flac").apply { mkdirs() }
        val wav = File(dir, "source.wav").apply { writeBytes(pcm.toWav()) }
        val out = File(dir, "encoded.flac")

        val result = FlacEncoder.encodeWav(wav, out)

        assertEquals(48_000, result.sampleRate)
        assertEquals(2, result.channels)
        assertEquals(frames.toLong(), result.frames)
        assertEquals(3, result.frameCount, "10000 帧按 4096 一块应是 3 帧")
        val bytes = out.readBytes()
        assertEquals("fLaC", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(34, ((bytes[5].toInt() and 0xFF) shl 16) or ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF))
        assertTrue(bytes.size > 1000, "产物太小，可疑：${bytes.size}")
    }
}
