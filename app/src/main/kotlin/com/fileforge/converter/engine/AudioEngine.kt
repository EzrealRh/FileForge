package com.fileforge.converter.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.fileforge.core.audio.AudioPlan
import com.fileforge.core.audio.AudioTarget
import com.fileforge.core.audio.WavHeader
import com.fileforge.core.ops.Operation
import com.fileforge.core.util.SizeInput
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * 音频：转格式（MP3 / FLAC / OGG… → M4A 或 WAV）和从视频里把声音拿出来。
 *
 * 系统没有 MP3 编码器，所以"转成 mp3"做不到，界面上也不给这个选项。
 *
 * 三条落法分开写，因为数据来源根本不是一条路：
 *  - **直通** —— 源本来就是 AAC 且目标是 m4a：不经解码器，原样搬采样，无损；
 *  - **重编** —— 解码成 PCM 再喂系统 AAC 编码器；
 *  - **WAV** —— 解码成 PCM，自己拼 RIFF 头（安卓没有 WAV 写手）。
 * 把直通硬塞进解码循环，写进 m4a 的就是裸 PCM 却标着 AAC：一个能打开却没声音的文件。
 *
 * 能脱离设备判死的判断都在 [com.fileforge.core.audio.AudioPlan] 里并配了单测。剩下这些
 * 只能真机验，所以两条硬规矩：① 每条路径都以"真的写出去过样本"为成功判据（muxer 一
 * start 就写文件头，空壳也有几 KB）；② muxer 只在**编码器自己报出输出格式之后**才
 * addTrack —— 提前 add 出去的 m4a 里没有 AudioSpecificConfig，本机能放，换个播放器就解不出码流。
 */
class AudioEngine(private val workspace: Workspace) {

    fun convert(
        item: WorkItem,
        operation: Operation.AudioConvert,
        onProgress: (Int) -> Unit = {},
    ): EngineOutput {
        val target = operation.target
        val wantBytes = operation.targetBytes
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(item.file.absolutePath)
            val trackMimes = (0 until extractor.trackCount).map { index ->
                runCatching { extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) }.getOrNull()
            }
            val track = AudioPlan.pickAudioTrack(trackMimes)
            require(track >= 0) {
                if (item.kind.isVideo) "这个视频里没有音轨" else "读不出音轨，这个文件可能不是音频"
            }
            val sourceMime = trackMimes[track] ?: error("音轨没有格式描述")
            // 只查不选等于没选：不 selectTrack 的话 readSampleData 第一下就返回 -1，
            // 整件事"跑完"却产出空文件（视频转码踩过同样的坑）
            extractor.selectTrack(track)
            val sourceFormat = extractor.getTrackFormat(track)
            val durationUs = sourceFormat.long(MediaFormat.KEY_DURATION) ?: 0L
            val sourceKbps = ((sourceFormat.long(MediaFormat.KEY_BIT_RATE) ?: 0L) / 1000).toInt()
                .takeIf { it > 0 }
            val kbps = AudioPlan.bitrateKbps(wantBytes, durationUs, sourceKbps)

            val notes = ArrayList<String>()
            if (wantBytes != null) notes += "按 ${SizeInput.format(wantBytes)} 目标算出 $kbps kbps"
            val passthrough = AudioPlan.canPassthrough(sourceMime, target)
            if (passthrough) notes += "音轨本来就是 AAC，原样搬出不重编"
            else require(AudioPlan.decodableMime(sourceMime)) { "系统里没有 $sourceMime 的解码器，这条音轨转不了" }

            val output = workspace.newStagingFile(target.extension)
            val written = try {
                when {
                    passthrough -> copyAcross(extractor, sourceFormat, output, durationUs, onProgress)
                    target == AudioTarget.Wav -> decodeToWav(extractor, sourceFormat, sourceMime, output, durationUs, onProgress)
                    else -> encodeToM4a(extractor, sourceFormat, sourceMime, output, kbps, durationUs, onProgress)
                }
            } catch (e: Throwable) {
                runCatching { output.delete() }
                throw e
            }
            require(AudioPlan.succeeded(written)) { "一个样本都没弄出来，这条音轨可能是空的" }
            notes += SizeInput.format(output.length())
            if (durationUs > 0) notes += "${"%.1f".format(durationUs / 1_000_000.0)} 秒"
            return EngineOutput(
                AudioPlan.outputName(item.name, target, fromVideo = item.kind.isVideo),
                output,
                notes.joinToString(" · "),
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    // ---- 路径一：AAC 原样搬进 m4a ------------------------------------------------

    private fun copyAcross(
        extractor: MediaExtractor,
        sourceFormat: MediaFormat,
        output: File,
        durationUs: Long,
        onProgress: (Int) -> Unit,
    ): Int {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var written = 0
        var buffer = ByteBuffer.allocate(
            (sourceFormat.integer(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0).coerceIn(SAMPLE_BUFFER_MIN, SAMPLE_BUFFER_MAX),
        )
        try {
            val muxTrack = muxer.addTrack(sourceFormat)
            muxer.start()
            while (true) {
                buffer.clear()
                var size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (size > buffer.capacity()) {
                    // 采样比缓冲区大：放大一次重来，不能截半帧写进去（那是杂音）
                    buffer = ByteBuffer.allocate(minOf(size, SAMPLE_BUFFER_MAX))
                    size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                }
                info.set(0, size, extractor.getSampleTime(), extractor.getSampleFlags())
                // csd 已经在 addTrack 用的格式里，再写一遍会让开头多出一段放不出声的字节
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    muxer.writeSampleData(muxTrack, buffer, info)
                    written++
                }
                extractor.advance()
                reportProgress(extractor.getSampleTime(), durationUs, onProgress)
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
        return written
    }

    // ---- 路径二：解码 + 重编成 AAC ----------------------------------------------

    private fun encodeToM4a(
        extractor: MediaExtractor,
        sourceFormat: MediaFormat,
        sourceMime: String,
        output: File,
        kbps: Int,
        durationUs: Long,
        onProgress: (Int) -> Unit,
    ): Int {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val encodeInfo = MediaCodec.BufferInfo()
        // 解码器不重采样，所以这两个值两端一致，按源配编码器
        val rate = sourceFormat.integer(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
        val channels = sourceFormat.integer(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
        var muxTrack = -1
        var written = 0
        var encoderDone = false

        /** 把编码器已经产出的采样搬进 muxer；muxer 只在编码器报格式后才起。 */
        fun pumpEncoder() {
            while (true) {
                val index = encoder.dequeueOutputBuffer(encodeInfo, 10_000L)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxTrack >= 0) continue
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                    }
                    index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    index < 0 -> return
                    else -> {
                        if (muxTrack < 0) {
                            encoder.releaseOutputBuffer(index, false)
                            return
                        }
                        val config = encodeInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && encodeInfo.size > 0) {
                            encoder.getOutputBuffer(index)?.let { sample ->
                                muxer.writeSampleData(muxTrack, sample, encodeInfo)
                                written++
                            }
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (encodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                        return
                    }
                }
            }
        }

        try {
            encoder.configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                },
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            encoder.start()

            decode(extractor, sourceFormat, sourceMime, durationUs, onProgress) { pcm, info ->
                // 输入面满了不能丢样本：丢了成品就比原曲短，而流程还会"成功"结束
                while (true) {
                    val index = encoder.dequeueInputBuffer(20_000L)
                    if (index >= 0) {
                        val input = encoder.getInputBuffer(index)
                        if (input != null) {
                            val size = minOf(info.size, input.capacity())
                            pcm.position(info.offset)
                            pcm.limit(info.offset + size)
                            input.clear()
                            input.put(pcm)
                            encoder.queueInputBuffer(index, 0, size, info.presentationTimeUs, info.flags)
                        }
                        break
                    }
                    pumpEncoder()
                }
                pumpEncoder()
            }

            val tail = encoder.dequeueInputBuffer(50_000L)
            if (tail >= 0) encoder.queueInputBuffer(tail, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            // 尾巴里还压着样本：冲到编码器报 EOS 为止，连续空手而归就放弃等待
            var idle = 0
            while (!encoderDone && idle < FINAL_SPINS) {
                val before = written
                pumpEncoder()
                idle = if (written == before) idle + 1 else 0
            }
            if (muxTrack >= 0) runCatching { muxer.stop() }
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { muxer.release() }
        }
        return written
    }

    // ---- 路径三：解码成裸 PCM 自己写 WAV ----------------------------------------

    private fun decodeToWav(
        extractor: MediaExtractor,
        sourceFormat: MediaFormat,
        sourceMime: String,
        output: File,
        durationUs: Long,
        onProgress: (Int) -> Unit,
    ): Int {
        var rate = sourceFormat.integer(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
        var channels = sourceFormat.integer(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
        var bits = 16
        var pcmBytes = 0L
        var blocks = 0
        val raf = RandomAccessFile(output, "rw")
        try {
            raf.setLength(0)
            raf.write(ByteArray(WavHeader.SIZE))   // 先占位，长度要等写完才知道
            decode(extractor, sourceFormat, sourceMime, durationUs, onProgress, onFormat = { format ->
                // 以解码器**输出**的格式为准：个别机型会重采样，按源文件写头就变速。
                // 位深也是 —— 绝大多数解码器给 16 位小端，但写错这一格是"能打开、内容是噪音"
                rate = format.integer(MediaFormat.KEY_SAMPLE_RATE) ?: rate
                channels = format.integer(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                bits = when (format.integer(MediaFormat.KEY_PCM_ENCODING)) {
                    AudioFormat.ENCODING_PCM_8BIT -> 8
                    AudioFormat.ENCODING_PCM_16BIT -> 16
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    AudioFormat.ENCODING_PCM_FLOAT -> 32
                    null -> bits
                    else -> error("这台机器解出来的是非整数位 PCM，写不出可靠的 WAV，改用 M4A")
                }
            }, onPcm = { pcm, info ->
                val chunk = ByteArray(info.size)
                pcm.position(info.offset)
                pcm.limit(info.offset + info.size)
                pcm.get(chunk)
                raf.write(chunk)
                pcmBytes += info.size
                blocks++
            })
            raf.seek(0)
            raf.write(WavHeader.of(pcmBytes, rate, channels, bits))
        } finally {
            runCatching { raf.close() }
        }
        return blocks
    }

    /**
     * 共用解码循环：喂输入 → 取输出 → 交给调用方。只此一份。
     * `onPcm` 放最后一个参数，两条路径都能用尾随 lambda 写；`onFormat` 在它前面带默认值。
     */
    private fun decode(
        extractor: MediaExtractor,
        sourceFormat: MediaFormat,
        sourceMime: String,
        durationUs: Long,
        onProgress: (Int) -> Unit,
        onFormat: (MediaFormat) -> Unit = {},
        onPcm: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    ) {
        val decoder = MediaCodec.createDecoderByType(sourceMime)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var idle = 0
        try {
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()
            while (!outputDone) {
                // 两头都取不到东西时不能无限空转：编解码器出问题这会变成死循环，界面永久卡在转换中
                if (inputDone && ++idle > MAX_IDLE_SPINS) error("解到一半停了，这个文件可能损坏或编码不支持")
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(20_000L)
                    if (index >= 0) {
                        val input = decoder.getInputBuffer(index)
                        val size = if (input != null) extractor.readSampleData(input, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.getSampleTime(), extractor.getSampleFlags())
                            extractor.advance()
                            reportProgress(extractor.getSampleTime(), durationUs, onProgress)
                        }
                    }
                }
                when (val out = decoder.dequeueOutputBuffer(info, 20_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(decoder.outputFormat)
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        if (out < 0) continue
                        idle = 0
                        if (info.size > 0) decoder.getOutputBuffer(out)?.let { onPcm(it, info) }
                        decoder.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
    }

    private fun reportProgress(sampleTimeUs: Long, durationUs: Long, onProgress: (Int) -> Unit) {
        if (durationUs <= 0 || sampleTimeUs < 0) return
        onProgress(((sampleTimeUs * 100) / durationUs).toInt().coerceIn(0, 99))
    }

    private fun MediaFormat.integer(key: String): Int? = runCatching { getInteger(key) }.getOrNull()
    private fun MediaFormat.long(key: String): Long? = runCatching { getLong(key) }.getOrNull()

    private companion object {
        const val SAMPLE_BUFFER_MIN = 256 * 1024
        const val SAMPLE_BUFFER_MAX = 4 * 1024 * 1024

        /** 解码循环连续空手而归的上限，超了就判失败而不是卡住界面。 */
        const val MAX_IDLE_SPINS = 3_000

        /** 收尾阶段等编码器吐完的轮数上限。 */
        const val FINAL_SPINS = 300
    }
}
