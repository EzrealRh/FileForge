package com.fileforge.core.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/** 16 位小端 PCM（解码器最常给的这一种）。音量、合并、降噪都在这一层做，能脱机单测。 */
class Pcm16(val sampleRate: Int, val channels: Int, val samples: ShortArray) {

    init {
        require(sampleRate > 0) { "采样率必须为正，拿到 $sampleRate" }
        require(channels in 1..8) { "声道数要在 1~8，拿到 $channels" }
        require(samples.size % channels == 0) { "样本数 ${samples.size} 不是声道数 $channels 的整数倍" }
    }

    val frames: Int get() = samples.size / channels

    fun gain(percent: Int): Pcm16 {
        require(percent in 0..400) { "音量要在 0%~400%，拿到 $percent%" }
        if (percent == 100) return this
        val factor = percent / 100.0
        val out = ShortArray(samples.size) { index ->
            (samples[index] * factor).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return Pcm16(sampleRate, channels, out)
    }

    /**
     * 轻降噪：一阶高通（削掉直流与低频隆隆声）+ 噪声门（低于阈值的样本清零）。
     * 这是保守版本：门限按档位给固定值，不做自适应 —— 宁可少削一点，也别把气声与尾音整段吃掉。
     */
    fun denoise(level: Int): Pcm16 {
        require(level in 1..3) { "降噪档位只有 1~3" }
        val threshold = when (level) { 1 -> 200; 2 -> 500; else -> 1000 }
        val out = ShortArray(samples.size)
        val prevX = IntArray(channels)
        val prevY = IntArray(channels)
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                val x = samples[index].toInt()
                val y = x - prevX[channel] + (prevY[channel] * 995 / 1000)
                prevX[channel] = x
                prevY[channel] = y
                out[index] = (if (abs(y) < threshold) 0 else y).coerceIn(-32768, 32767).toShort()
            }
        }
        return Pcm16(sampleRate, channels, out)
    }

    fun toWav(): ByteArray {
        val data = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = sample.toInt()
            data[index * 2] = (value and 0xFF).toByte()
            data[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        return WavHeader.of(data.size.toLong(), sampleRate, channels, 16) + data
    }

    companion object {

        fun concat(parts: List<Pcm16>): Pcm16 {
            require(parts.isNotEmpty()) { "没有可合并的音频" }
            val first = parts.first()
            parts.forEachIndexed { index, part ->
                require(part.sampleRate == first.sampleRate && part.channels == first.channels) {
                    "第 ${index + 1} 份是 ${part.sampleRate}Hz/${part.channels} 声道，" +
                        "与第一份 ${first.sampleRate}Hz/${first.channels} 声道对不上；先统一转成同一种再合并"
                }
            }
            val total = parts.sumOf { it.samples.size }
            val merged = ShortArray(total)
            var at = 0
            parts.forEach { part ->
                part.samples.copyInto(merged, at)
                at += part.samples.size
            }
            return Pcm16(first.sampleRate, first.channels, merged)
        }

        /**
         * 读我们自己写的那种 44 字节头 WAV。只认 16 位整数 PCM ——
         * 解码器偶尔给 8/24/float，那几种在这里直说，不按 16 位硬读成噪音。
         */
        fun fromWav(bytes: ByteArray): Pcm16 {
            require(bytes.size >= WavHeader.SIZE) { "这份 WAV 太短" }
            fun ascii(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
            require(ascii(0) == "RIFF" && ascii(8) == "WAVE") { "不是 RIFF/WAVE 文件" }
            fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
            fun u32(at: Int) = (bytes[at].toInt() and 0xFF).toLong() or
                ((bytes[at + 1].toInt() and 0xFF).toLong() shl 8) or
                ((bytes[at + 2].toInt() and 0xFF).toLong() shl 16) or
                ((bytes[at + 3].toInt() and 0xFF).toLong() shl 24)
            val format = u16(20)
            require(format == WavHeader.FORMAT_PCM) { "WAV 的格式标签是 $format（不是整数 PCM），这一版不处理" }
            val channels = u16(22)
            val rate = u32(24).toInt()
            val bits = u16(34)
            require(bits == 16) { "WAV 是 $bits 位，音量/合并/降噪这一版只处理 16 位" }
            val dataSize = u32(40).toInt()
            require(dataSize >= 0 && WavHeader.SIZE + dataSize <= bytes.size) { "WAV 的 data 长度越界" }
            require(dataSize % 2 == 0) { "16 位 PCM 的 data 长度是奇数，文件截断了" }
            val count = dataSize / 2
            val samples = ShortArray(count) { index ->
                val at = WavHeader.SIZE + index * 2
                (u16(at)).toShort()
            }
            return Pcm16(rate, channels, samples)
        }
    }
}
