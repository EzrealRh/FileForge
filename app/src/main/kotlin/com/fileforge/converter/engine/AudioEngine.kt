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

    /**
     * 音频截取：按起止秒数把这一段交出去。AAC 源选 M4A 时原样搬运（无损秒出），
     * 其他按目标格式解了重编或落 WAV；endSecond ≤ 0 表示到片尾。
     */
    fun trim(item: WorkItem, operation: Operation.AudioTrim, onProgress: (Int) -> Unit = {}): EngineOutput {
        val startUs = (operation.startSecond.coerceAtLeast(0.0) * 1_000_000).toLong()
        val endUs = if (operation.endSecond <= 0.0) Long.MAX_VALUE else (operation.endSecond * 1_000_000).toLong()
        require(endUs > startUs) { "结束时间要比开始时间晚" }
        val range = startUs..endUs

        val target = operation.target
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
            extractor.selectTrack(track)
            val sourceFormat = extractor.getTrackFormat(track)
            val durationUs = sourceFormat.long(MediaFormat.KEY_DURATION) ?: 0L
            val sourceKbps = ((sourceFormat.long(MediaFormat.KEY_BIT_RATE) ?: 0L) / 1000).toInt().takeIf { it > 0 }
            val passthrough = AudioPlan.canPassthrough(sourceMime, target)
            if (!passthrough) require(AudioPlan.decodableMime(sourceMime)) { "系统里没有 $sourceMime 的解码器，这条音轨截不了" }

            val output = workspace.newStagingFile(target.extension)
            val written = try {
                when {
                    passthrough -> copyAcross(extractor, sourceFormat, output, durationUs, onProgress, range)
                    target == AudioTarget.Wav -> decodeToWav(extractor, sourceFormat, sourceMime, output, durationUs, onProgress, range)
                    else -> encodeToM4a(
                        extractor, sourceFormat, sourceMime, output,
                        AudioPlan.bitrateKbps(null, durationUs, sourceKbps), durationUs, onProgress, range,
                    )
                }
            } catch (e: Throwable) {
                runCatching { output.delete() }
                throw e
            }
            require(written > 0) { "截取范围里没有音频：起止时间超出长度了？" }
            val span = "${operation.startSecond} 秒到 " + (if (operation.endSecond <= 0.0) "片尾" else "${operation.endSecond} 秒")
            val how = when {
                passthrough -> "原样搬运不重编"
                target == AudioTarget.Wav -> "解码落成 WAV"
                else -> "重编成 AAC"
            }
            return EngineOutput(
                AudioPlan.outputName(item.name, target, fromVideo = item.kind.isVideo),
                output,
                "$span · $how · ${SizeInput.format(output.length())}",
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
        range: LongRange? = null,
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
            // 截取模式：从起点（落在前一个采样上）开始，越过终点就收
            range?.let { extractor.seekTo(it.first, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
            while (true) {
                buffer.clear()
                var size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (range != null && extractor.getSampleTime() > range.last) break
                if (size > buffer.capacity()) {
                    // 采样比缓冲区大：放大一次重来，不能截半帧写进去（那是杂音）
                    buffer = ByteBuffer.allocate(minOf(size, SAMPLE_BUFFER_MAX))
                    size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                }
                if (range != null && extractor.getSampleTime() < range.first) {
                    extractor.advance()
                    continue
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
        range: LongRange? = null,
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
        // 截取时输出时间戳从第一个写进的采样起算，不然成品开头是一段静音
        var firstPts = -1L

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
                                if (firstPts < 0) firstPts = encodeInfo.presentationTimeUs
                                val out = MediaCodec.BufferInfo()
                                out.set(
                                    encodeInfo.offset,
                                    encodeInfo.size,
                                    (encodeInfo.presentationTimeUs - firstPts).coerceAtLeast(0),
                                    encodeInfo.flags,
                                )
                                muxer.writeSampleData(muxTrack, sample, out)
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

            // 截取：从起点开始解码，越过终点就收；范围外的 PCM 块不进编码器
            range?.let { extractor.seekTo(it.first, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
            decode(extractor, sourceFormat, sourceMime, durationUs, onProgress, stopAtUs = range?.last ?: Long.MAX_VALUE) { pcm, info ->
                if (range != null && info.presentationTimeUs !in range) return@decode
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
        range: LongRange? = null,
    ): Int {
        var rate = sourceFormat.integer(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
        var channels = sourceFormat.integer(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
        var bits = 16
        var floatPcm = false
        var pcmBytes = 0L
        var blocks = 0
        val raf = RandomAccessFile(output, "rw")
        try {
            range?.let { extractor.seekTo(it.first, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
            raf.setLength(0)
            raf.write(ByteArray(WavHeader.SIZE))   // 先占位，长度要等写完才知道
            decode(extractor, sourceFormat, sourceMime, durationUs, onProgress, stopAtUs = range?.last ?: Long.MAX_VALUE, onFormat = { format ->
                // 以解码器**输出**的格式为准：个别机型会重采样，按源文件写头就变速。
                // 位深也是 —— 绝大多数解码器给 16 位小端，但写错这一格是"能打开、内容是噪音"
                rate = format.integer(MediaFormat.KEY_SAMPLE_RATE) ?: rate
                channels = format.integer(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                bits = when (format.integer(MediaFormat.KEY_PCM_ENCODING)) {
                    AudioFormat.ENCODING_PCM_8BIT -> 8
                    AudioFormat.ENCODING_PCM_16BIT -> 16
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    AudioFormat.ENCODING_PCM_FLOAT -> {
                        floatPcm = true
                        32
                    }
                    null -> bits
                    else -> error("这台机器解出来的是非整数位 PCM，写不出可靠的 WAV，改用 M4A")
                }
            }, onPcm = { pcm, info ->
                // 截取：范围外的 PCM 块跳过不落盘
                if (range == null || info.presentationTimeUs in range) {
                    val chunk = ByteArray(info.size)
                    pcm.position(info.offset)
                    pcm.limit(info.offset + info.size)
                    pcm.get(chunk)
                    raf.write(chunk)
                    pcmBytes += info.size
                    blocks++
                }
            })
            raf.seek(0)
            // float 解码输出必须写 IEEE float 的格式标签（3）：照整数 PCM 写的话文件能打开、放出来是噪音
            raf.write(WavHeader.of(pcmBytes, rate, channels, bits, if (floatPcm) WavHeader.FORMAT_IEEE_FLOAT else WavHeader.FORMAT_PCM))
            // RIFF 规定奇数长的块后面垫一个字节（不计入长度字段）：8 位单声道常见奇数长
            if (pcmBytes % 2 == 1L) raf.write(0)
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
        stopAtUs: Long = Long.MAX_VALUE,
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
                        // 截取的止点到了就提前收尾，别把后面的也解出来
                        if (size < 0 || extractor.getSampleTime() > stopAtUs) {
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
