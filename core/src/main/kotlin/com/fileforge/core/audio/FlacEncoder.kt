package com.fileforge.core.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 最小可用的 FLAC 无损编码器：STREAMINFO + 每帧 verbatim 子帧（不做预测/熵编码）。
 *
 * 为什么先做 verbatim：它不改变样本、实现面小，能先把"安卓侧能写出别的播放器认的 FLAC"
 * 这件事钉死；压缩率不如 libFLAC，但不会为了省体积偷偷动样本。所有 PCM 都从临时 WAV
 * 按块读、按块写，堆里只放一个 4096 帧的块。
 */
object FlacEncoder {

    data class Result(
        val sampleRate: Int,
        val channels: Int,
        val frames: Long,
        val frameCount: Int,
    )

    private const val BLOCK = 4096

    fun encodeWav(wav: File, output: File): Result {
        val header = readWavHeader(wav)
        require(header.frames > 0) { "这份 WAV 一个样本都没有" }
        val md5 = md5OfData(wav, header)
        BufferedOutputStream(output.outputStream(), 1 shl 16).use { out ->
            out.write("fLaC".toByteArray(Charsets.US_ASCII))
            writeStreamInfo(out, header, md5)
            var frameIndex = 0
            RandomAccessFile(wav, "r").use { raf ->
                raf.seek(header.dataOffset)
                val blockBytes = BLOCK * header.channels * 2
                val buffer = ByteArray(blockBytes)
                var framesLeft = header.frames
                while (framesLeft > 0) {
                    val frames = minOf(BLOCK.toLong(), framesLeft).toInt()
                    val bytes = frames * header.channels * 2
                    raf.readFully(buffer, 0, bytes)
                    writeFrame(out, buffer, frames, frameIndex, header.channels)
                    frameIndex++
                    framesLeft -= frames
                }
            }
            return Result(header.sampleRate, header.channels, header.frames, frameIndex)
        }
    }

    private class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val dataOffset: Long,
        val dataSize: Long,
        val frames: Long,
    )

    private fun readWavHeader(file: File): WavInfo {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= 44) { "WAV 太短" }
            val head = ByteArray(12)
            raf.readFully(head)
            require(String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(head, 8, 4, Charsets.US_ASCII) == "WAVE") { "不是 RIFF/WAVE" }
            var at = 12L
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var dataOffset = -1L
            var dataSize = -1L
            while (at + 8 <= raf.length()) {
                raf.seek(at)
                val chunk = ByteArray(8)
                raf.readFully(chunk)
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = le32(chunk, 4)
                val body = at + 8
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(16)
                        raf.readFully(fmt)
                        val format = le16(fmt, 0)
                        require(format == 1) { "FLAC 输出只支持整数 PCM 的 WAV（格式标签 $format）" }
                        channels = le16(fmt, 2)
                        sampleRate = le32(fmt, 4).toInt()
                        bits = le16(fmt, 14)
                        require(bits == 16) { "FLAC 输出只支持 16 位 PCM（这份是 $bits 位）" }
                        require(channels in 1..8) { "声道数要在 1~8（这份是 $channels）" }
                    }
                    "data" -> {
                        dataOffset = body
                        dataSize = size
                    }
                }
                require(size >= 0) { "WAV 块长度是负数" }
                at = body + size + (size and 1L)
                if (dataOffset >= 0 && sampleRate > 0) break
            }
            require(dataOffset > 0 && dataSize > 0) { "WAV 里没找到 fmt / data 块" }
            require(dataSize % (channels * 2L) == 0L) { "WAV 的 data 不是整帧" }
            require(dataOffset + dataSize <= file.length()) { "WAV 的 data 越界，文件截断了" }
            return WavInfo(sampleRate, channels, dataOffset, dataSize, dataSize / (channels * 2L))
        }
    }

    private fun md5OfData(file: File, info: WavInfo): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset)
            val buffer = ByteArray(1 shl 16)
            var left = info.dataSize
            while (left > 0) {
                val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n <= 0) break
                digest.update(buffer, 0, n)
                left -= n
            }
            require(left == 0L) { "WAV 的 data 读少了" }
        }
        return digest.digest()
    }

    private fun writeStreamInfo(out: OutputStream, info: WavInfo, md5: ByteArray) {
        val total = info.frames
        require(total < (1L shl 36)) { "样本数超出 FLAC 的 36 位上限" }
        val maxBlock = minOf(BLOCK.toLong(), total).toInt()
        val lastBlock = if (total % BLOCK == 0L) BLOCK else (total % BLOCK).toInt()
        val minBlock = if (total < BLOCK) total.toInt() else lastBlock
        out.write(0x80)                 // last metadata block, type STREAMINFO (0)
        out.write(0x00); out.write(0x00); out.write(0x22)   // 34 字节
        writeBe16(out, minBlock)
        writeBe16(out, maxBlock)
        // min/max frame size 未知写 0（24 位）
        out.write(0); out.write(0); out.write(0)
        out.write(0); out.write(0); out.write(0)
        val packed = (info.sampleRate.toLong() shl 44) or
            ((info.channels - 1).toLong() shl 41) or
            (15L shl 36) or total
        for (shift in 56 downTo 0 step 8) out.write(((packed shr shift) and 0xFF).toInt())
        out.write(md5)
    }

    private fun writeFrame(out: OutputStream, pcm: ByteArray, frames: Int, frameIndex: Int, channels: Int) {
        val header = ArrayList<Byte>(4 + 8)
        header += 0xFF.toByte()
        header += 0xF8.toByte()
        header += 0x70.toByte()         // block size code 7, sample rate code 0
        header += (((channels - 1) shl 4) or (4 shl 1)).toByte()  // channel assignment, sample size 16-bit, reserved 0
        frameNumberUtf8(frameIndex).forEach { header += it }
        header += ((frames - 1) shr 8).toByte()
        header += ((frames - 1) and 0xFF).toByte()
        val crc8 = crc8(header.toByteArray())
        header += crc8.toByte()

        val frame = java.io.ByteArrayOutputStream(header.size + frames * channels * 2 + 2)
        header.forEach { frame.write(it.toInt()) }
        for (channel in 0 until channels) {
            frame.write(0x02)           // zero bit + verbatim type 000001 + wasted bits 0
            var at = channel * 2
            repeat(frames) {
                // WAV 是 16 位小端，FLAC 子帧样本是大端
                val sample = ((pcm[at + 1].toInt() and 0xFF) shl 8) or (pcm[at].toInt() and 0xFF)
                frame.write((sample shr 8) and 0xFF)
                frame.write(sample and 0xFF)
                at += channels * 2
            }
        }
        val body = frame.toByteArray()
        val crc16 = crc16(body)
        out.write(body)
        out.write((crc16 shr 8) and 0xFF)
        out.write(crc16 and 0xFF)
    }

    private fun frameNumberUtf8(value: Int): ByteArray {
        require(value >= 0) { "帧号不能为负" }
        if (value < 0x80) return byteArrayOf(value.toByte())
        val ranges = listOf(0x800L to 2, 0x10000L to 3, 0x200000L to 4, 0x4000000L to 5, 0x80000000L to 6)
        var count = 6
        for ((limit, bytes) in ranges) {
            if (value.toLong() < limit) { count = bytes; break }
        }
        require(count in 2..6) { "帧号 $value 超出 UTF-8 可编码范围" }
        val out = ByteArray(count)
        var v = value
        for (i in count - 1 downTo 1) {
            out[i] = (0x80 or (v and 0x3F)).toByte()
            v = v ushr 6
        }
        val prefix = (0xFF shl (8 - count)) and 0xFF
        out[0] = (prefix or v).toByte()
        return out
    }

    private fun writeBe16(out: OutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun crc8(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    private fun crc16(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    private fun le16(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or ((bytes[at + 3].toLong() and 0xFF) shl 24)
}
