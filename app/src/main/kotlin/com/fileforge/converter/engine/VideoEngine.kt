package com.fileforge.converter.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.view.Surface
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.Operation
import com.fileforge.core.ops.VideoFormat
import com.fileforge.core.util.SizeInput
import com.fileforge.core.video.VideoBitratePlan
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 视频压缩走硬件路径：解码器直接输出到编码器的输入面，帧数据不进 Java 堆；
 * 缩放交给编码器（API 29 起支持），音轨原样搬运不重编码。
 */
class VideoEngine(private val workspace: Workspace, private val images: ImageEngine) {

    suspend fun compress(item: WorkItem, operation: Operation.CompressVideo, onProgress: (Int) -> Unit): EngineOutput {
        val output = workspace.newStagingFile(operation.format.extension)
        val mime = when (operation.format) {
            VideoFormat.Mp4 -> MediaFormat.MIMETYPE_VIDEO_AVC
            VideoFormat.WebM -> MediaFormat.MIMETYPE_VIDEO_VP8
        }
        val notes = ArrayList<String>()
        val rotation = rotationOf(item)
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var job: Transcode? = null
        var surface: Surface? = null
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(item.file.absolutePath)
            val videoTrack = videoExtractor.selectFirst("video/")
            require(videoTrack >= 0) { "这个文件里没有视频轨" }
            val sourceFormat = videoExtractor.getTrackFormat(videoTrack)
            val sourceWidth = sourceFormat.integer(MediaFormat.KEY_WIDTH) ?: error("读不到画面尺寸")
            val sourceHeight = sourceFormat.integer(MediaFormat.KEY_HEIGHT) ?: error("读不到画面尺寸")
            // 编码器按源文件的存储尺寸配置，旋转交给 muxer 的 orientation hint，
            // 否则解码器已经转正的画面会被再旋一次。
            val target = even(sourceWidth) to even(sourceHeight)
            // 编码器的输入面不接受尺寸缩放（SDK 没有对外的缩放开关），所以只压码率，
            // 想改分辨率得走 GPU 通路，那是另一件事，这里如实告诉用户。
            if (operation.maxEdge > 0 && max(sourceWidth, sourceHeight) > operation.maxEdge) {
                notes += "只压码率，不改分辨率"
            }
            if (operation.format == VideoFormat.WebM) notes += "WebM 输出不带音轨"

            val keepAudio = operation.format == VideoFormat.Mp4
            if (keepAudio) audioExtractor.setDataSource(item.file.absolutePath)
            val audioTrack = if (keepAudio) audioExtractor.selectFirst("audio/") else -1
            if (keepAudio && audioTrack < 0) notes += "没有音轨可保留"
            if (audioTrack >= 0) audioExtractor.selectTrack(audioTrack)
            // 音轨是原样搬运不重编码的，所以预留体积要按源音轨的真实码率算
            val audioBitsPerSecond = if (audioTrack < 0) {
                0
            } else {
                audioExtractor.getTrackFormat(audioTrack).integer(MediaFormat.KEY_BIT_RATE)
                    ?.coerceIn(32_000, 320_000) ?: 128_000
            }

            val durationUs = sourceFormat.long(MediaFormat.KEY_DURATION) ?: 0L
            val fromTarget = operation.targetBytes?.let { wanted ->
                VideoBitratePlan.videoBitrateBps(wanted, durationUs, audioBitsPerSecond)
                    ?.also { notes += "按 ${SizeInput.format(wanted)} 目标算出 ${it / 1000} kbps（音轨占 ${audioBitsPerSecond / 1000} kbps）" }
            }
            if (operation.targetBytes != null && fromTarget == null) {
                notes += "目标体积算不出码率（时长读不到或比音轨还小），改用 ${operation.videoBitrateKbps} kbps"
            }
            val videoBitsPerSecond = (fromTarget ?: operation.videoBitrateKbps * 1000).coerceIn(150_000, 80_000_000)

            val encoder = MediaCodec.createEncoderByType(mime).apply {
                configure(
                    MediaFormat.createVideoFormat(mime, target.first, target.second).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, videoBitsPerSecond)
                        setInteger(MediaFormat.KEY_FRAME_RATE, sourceFormat.integer(MediaFormat.KEY_FRAME_RATE)?.coerceIn(1, 120) ?: 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    },
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                // createInputSurface() 只在 Configured 状态有效，必须在 start() 之前取
                surface = createInputSurface()
                start()
            }
            val decoder = MediaCodec.createDecoderByType(sourceFormat.string(MediaFormat.KEY_MIME) ?: "")
            try {
                decoder.configure(sourceFormat, surface, null, 0)
                decoder.start()
            } catch (error: Exception) {
                throw IllegalStateException("这台设备解不了这个视频的编码格式：${error.message}", error)
            }

            muxer = MediaMuxer(output.absolutePath, muxerFormat(operation.format)).apply {
                setOrientationHint(rotation)
            }
            job = Transcode(
                decoder = decoder,
                encoder = encoder,
                muxer = muxer!!,
                videoExtractor = videoExtractor,
                audioExtractor = if (audioTrack >= 0) audioExtractor else null,
                audioTrackIndex = audioTrack,
            )

            var decoded = false
            while (!job.encodeDone) {
                currentCoroutineContext().ensureActive()
                if (!decoded) job.feedDecoder()
                job.drainDecoder()
                job.drainEncoder()
                job.pumpAudio()
                if (job.decodeDone && !job.eosSent) job.signalEncoderEnd()
                decoded = job.decodeDone
                if (durationUs > 0) {
                    onProgress(((job.lastPtsUs * 100) / durationUs).toInt().coerceIn(0, 99))
                }
            }
            job.finish()
            onProgress(100)
        } catch (error: Exception) {
            // 半写的 mp4 留着就是坏文件，直接删；调用方只会看到失败条目
            runCatching { output.delete() }
            if (error is IllegalStateException) throw error
            throw IllegalStateException("转码失败：${error.javaClass.simpleName} ${error.message}", error)
        } finally {
            runCatching { job?.release() }
            if (job == null) runCatching { muxer?.release() }
            runCatching { surface?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }

        val produced = output.length()
        require(produced > 0) { "转码没有产出数据" }
        if (produced >= item.size) notes += "结果不比原文件小，说明源文件码率已经很低"
        return EngineOutput(
            OutputNaming.tagged(item.name, "压缩", operation.format.extension),
            output,
            "${SizeInput.format(produced)} ← ${SizeInput.format(item.size)}" +
                if (notes.isEmpty()) "" else "（${notes.joinToString("，")}）",
        )
    }

    private class Transcode(
        val decoder: MediaCodec,
        val encoder: MediaCodec,
        val muxer: MediaMuxer,
        val videoExtractor: MediaExtractor,
        val audioExtractor: MediaExtractor?,
        audioTrackIndex: Int,
    ) {
        private val decodeInfo = MediaCodec.BufferInfo()
        private val encodeInfo = MediaCodec.BufferInfo()
        private val audioInfo = MediaCodec.BufferInfo()
        private val audioBuffer: ByteBuffer? = if (audioExtractor != null) ByteBuffer.allocate(512 * 1024) else null

        var decodeDone = false
        var encodeDone = false
        var eosSent = false
        private var streamEndQueued = false
        var lastPtsUs = 0L

        private var videoTrack = -1
        private var muxerStarted = false
        private val audioTrack = if (audioExtractor != null && audioTrackIndex >= 0) {
            muxer.addTrack(audioExtractor.getTrackFormat(audioTrackIndex))
        } else {
            -1
        }

        fun feedDecoder() {
            val index = decoder.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return
            val target = decoder.getInputBuffer(index) ?: return
            val size = videoExtractor.readSampleData(target, 0)
            if (size < 0) {
                if (!streamEndQueued) {
                    decoder.queueInputBuffer(index, 0, 0, lastPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    streamEndQueued = true
                }
            } else {
                lastPtsUs = videoExtractor.sampleTime
                decoder.queueInputBuffer(index, 0, size, videoExtractor.sampleTime, 0)
                videoExtractor.advance()
            }
        }

        fun drainDecoder() {
            val index = decoder.dequeueOutputBuffer(decodeInfo, TIMEOUT_US)
            if (index < 0) return
            decoder.releaseOutputBuffer(index, true)
            if (decodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
        }

        fun signalEncoderEnd() {
            encoder.signalEndOfInputStream()
            eosSent = true
        }

        fun drainEncoder() {
            val index = encoder.dequeueOutputBuffer(encodeInfo, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (videoTrack >= 0) return
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                }
                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)
                    val config = encodeInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!muxerStarted && videoTrack >= 0 && !config) {
                        muxer.start()
                        muxerStarted = true
                    }
                    if (muxerStarted && !config && encodeInfo.size > 0 && buffer != null) {
                        muxer.writeSampleData(videoTrack, buffer, encodeInfo)
                    }
                    if (encodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encodeDone = true
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        }

        fun pumpAudio() {
            val extractor = audioExtractor ?: return
            val buffer = audioBuffer ?: return
            if (!muxerStarted) return
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return
            audioInfo.offset = 0
            audioInfo.size = size
            audioInfo.presentationTimeUs = extractor.sampleTime
            audioInfo.flags = extractor.sampleFlags
            buffer.flip()
            muxer.writeSampleData(audioTrack, buffer, audioInfo)
            extractor.advance()
        }

        fun finish() {
            if (muxerStarted) muxer.stop()
        }

        fun release() {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            // 不 stop 就 release 的话，mp4 缺 moov 尾，文件打不开
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    /** 抽一帧当图片：走和转 GIF 同一套顺序解码，秒数指到哪帧就是哪帧。 */
    fun still(item: WorkItem, operation: Operation.VideoToImage): EngineOutput {
        val meta = videoDisplayMeta(item.file)
        require(meta.durationUs > 0) { "读不到时长，这个视频可能损坏" }
        // 留 0.1 秒余量：正好指到结尾时解不出采样，报错会误导成"不支持这个编码"
        val second = operation.second.coerceIn(0.0, max(0.0, meta.durationUs / 1_000_000.0 - 0.1))
        val longest = max(meta.width, meta.height)
        val scale = if (operation.maxEdge > 0) min(1f, operation.maxEdge.toFloat() / longest) else 1f
        val width = (meta.width * scale).roundToInt().coerceAtLeast(2)
        val height = (meta.height * scale).roundToInt().coerceAtLeast(2)

        val taken = VideoFrameSequence.extract(
            file = item.file,
            startUs = (second * 1_000_000).toLong(),
            windowUs = 1L,
            frameCount = 1,
            stepUs = 1L,
            targetWidth = width,
            targetHeight = height,
            rotation = meta.rotation,
        )
        val bitmap = taken.bitmaps.firstOrNull() ?: error("这一帧解不出来，换个秒数试试")
        val extension = operation.format.extension
        val bytes = images.encode(bitmap, operation.format, operation.quality)
        bitmap.recycle()

        val tag = if (second < 0.05) "封面" else "${"%.1f".format(second)}秒"
        return EngineOutput(
            OutputNaming.tagged(item.name, tag, extension),
            workspace.newStagingFile(extension).apply { writeBytes(bytes) },
            "$width x $height · 取自第 ${"%.1f".format(second)} 秒 · ${SizeInput.format(bytes.size.toLong())}",
        )
    }

    /** 编码器要求偶数边长，奇数会 configure 失败。 */
    private fun even(value: Int): Int = (if (value % 2 == 0) value else value - 1).coerceAtLeast(2)

    private fun muxerFormat(format: VideoFormat): Int = when (format) {
        VideoFormat.Mp4 -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        VideoFormat.WebM -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
    }

    private fun MediaExtractor.selectFirst(prefix: String): Int =
        (0 until trackCount).firstOrNull { index ->
            getTrackFormat(index).string(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        } ?: -1

    private fun MediaFormat.string(key: String): String? = runCatching { getString(key) }.getOrNull()
    private fun MediaFormat.integer(key: String): Int? = runCatching { getInteger(key) }.getOrNull()
    private fun MediaFormat.long(key: String): Long? = runCatching { getLong(key) }.getOrNull()

    private fun rotationOf(item: WorkItem): Int = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(item.file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(0)

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
