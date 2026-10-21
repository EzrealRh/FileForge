package com.fileforge.core

import com.fileforge.core.audio.Pcm16
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Pcm16Test {

    private fun pcm(vararg values: Int, rate: Int = 44_100, channels: Int = 1) =
        Pcm16(rate, channels, ShortArray(values.size) { values[it].toShort() })

    @Test
    fun `WAV 写出去再读回来一个样本都不差`() {
        val source = pcm(0, 1, -1, 32767, -32768, 1234, -5678)
        val again = Pcm16.fromWav(source.toWav())
        assertEquals(source.sampleRate, again.sampleRate)
        assertEquals(source.channels, again.channels)
        assertEquals(source.samples.toList(), again.samples.toList())
    }

    @Test
    fun `音量按百分比放大缩小并夹在 16 位里`() {
        val source = pcm(1000, -1000, 20000, -20000)
        assertEquals(listOf(2000, -2000, 32767, -32768), source.gain(200).samples.map { it.toInt() })
        assertEquals(listOf(500, -500, 10000, -10000), source.gain(50).samples.map { it.toInt() })
        assertTrue(source.gain(0).samples.all { it.toInt() == 0 }, "0% 就是静音")
        assertThrows(IllegalArgumentException::class.java) { source.gain(401) }
    }

    @Test
    fun `合并要求采样率与声道一致`() {
        val a = pcm(1, 2, 3)
        val b = pcm(4, 5)
        assertEquals(listOf(1, 2, 3, 4, 5), Pcm16.concat(listOf(a, b)).samples.map { it.toInt() })
        assertThrows(IllegalArgumentException::class.java) { Pcm16.concat(listOf(a, pcm(1, rate = 48_000))) }
        assertThrows(IllegalArgumentException::class.java) { Pcm16.concat(listOf(a, pcm(1, 2, channels = 2))) }
    }

    @Test
    fun `轻降噪把很轻的信号压到零、响的留着`() {
        val quiet = ShortArray(200) { if (it % 2 == 0) 100 else -100 }
        val loud = ShortArray(200) { if (it % 2 == 0) 10_000 else -10_000 }
        val mixed = Pcm16(44_100, 1, quiet + loud)
        val cleaned = mixed.denoise(2)
        assertTrue(cleaned.samples.take(190).all { it.toInt() == 0 }, "轻信号应该被门限吃掉")
        assertTrue(cleaned.samples.drop(200).any { it.toInt() != 0 }, "响的信号不能被一起吃掉")
        assertThrows(IllegalArgumentException::class.java) { mixed.denoise(4) }
    }

    @Test
    fun `不是 16 位整数 PCM 就直说`() {
        val bytes = pcm(1, 2).toWav()
        bytes[34] = 8                       // bitsPerSample 改成 8
        assertThrows(IllegalArgumentException::class.java) { Pcm16.fromWav(bytes) }
        val float = pcm(1, 2).toWav()
        float[20] = 3                       // formatTag 改成 IEEE float
        assertThrows(IllegalArgumentException::class.java) { Pcm16.fromWav(float) }
    }
}
