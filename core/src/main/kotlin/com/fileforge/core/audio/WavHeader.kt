package com.fileforge.core.audio

/**
 * 标准 44 字节 RIFF/WAVE 头（PCM 无扩展块）。
 *
 * 放在 `:core` 而不是引擎里，是因为它是这套音频功能里唯一**不依赖任何安卓 API**的产物：
 * 头写错一个偏移，Windows 媒体播放器、ffmpeg、手机相册对它的反应都不一样，
 * 而这些只能在字节层钉死 —— 本机就能拿 Python 的 wave 模块交叉验，不用等真机。
 */
object WavHeader {

    /** 固定头长度；PCM 数据从这一 byte 之后开始。 */
    const val SIZE = 44

    /** 安卓 MediaCodec 解码 PCM 出来恒为 16 位小端，所以引擎只用这一条。 */
    fun pcm16(pcmBytes: Long, sampleRate: Int, channels: Int): ByteArray =
        of(pcmBytes, sampleRate, channels, 16)

    /**
     * `pcmBytes` 必须是 `blockAlign` 的整数倍（半帧都不能有），否则不同解码器会各按各的
     * 理解截断 —— 调用方按解码器给的长度累加，本来就不会出现半帧。
     */
    fun of(pcmBytes: Long, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        require(sampleRate > 0) { "采样率必须为正，拿到 $sampleRate" }
        require(channels in 1..8) { "声道数要在 1~8，拿到 $channels" }
        require(bitsPerSample % 8 == 0) { "位深得是 8 的整数倍，拿到 $bitsPerSample" }
        require(pcmBytes >= 0) { "PCM 长度不能为负，拿到 $pcmBytes" }

        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate.toLong() * blockAlign

        val out = ByteArray(SIZE)
        ascii(out, 0, "RIFF")
        u32(out, 4, 36 + pcmBytes)          // 整份文件长度减 8：RIFF 块自己的 size 不含前 8 字节
        ascii(out, 8, "WAVE")
        ascii(out, 12, "fmt ")
        u32(out, 16, 16)                    // fmt 块体长度：PCM 固定 16
        u16(out, 20, 1)                     // 1 = 无压缩 PCM
        u16(out, 22, channels)
        u32(out, 24, sampleRate.toLong())
        u32(out, 28, byteRate)
        u16(out, 32, blockAlign)
        u16(out, 34, bitsPerSample)
        ascii(out, 36, "data")
        u32(out, 40, pcmBytes)
        return out
    }

    /** 写完之后回填长度用的：把头重算一遍盖回文件开头。 */
    fun rewrite(target: ByteArray, pcmBytes: Long, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        require(target.size >= SIZE) { "缓冲区放不下 44 字节头" }
        of(pcmBytes, sampleRate, channels, bitsPerSample).copyInto(target, 0, 0, SIZE)
    }

    private fun ascii(to: ByteArray, at: Int, text: String) =
        text.forEachIndexed { i, ch -> to[at + i] = ch.code.toByte() }

    private fun u16(to: ByteArray, at: Int, v: Int) {
        to[at] = (v and 0xFF).toByte()
        to[at + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun u32(to: ByteArray, at: Int, v: Long) {
        to[at] = (v and 0xFF).toByte()
        to[at + 1] = ((v ushr 8) and 0xFF).toByte()
        to[at + 2] = ((v ushr 16) and 0xFF).toByte()
        to[at + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
