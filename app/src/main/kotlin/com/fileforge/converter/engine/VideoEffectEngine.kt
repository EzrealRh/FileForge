package com.fileforge.converter.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.fileforge.core.image.ImageGeometry
import com.fileforge.core.model.FileKind
import com.fileforge.core.naming.OutputNaming
import com.fileforge.core.ops.Operation
import com.fileforge.core.text.Cue
import com.fileforge.core.text.SubtitleFormat
import com.fileforge.core.text.Subtitles
import com.fileforge.core.text.TextCodecs
import com.fileforge.core.text.TimedText
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 需要改画面的视频操作：裁画面 / 加水印 / 烧字幕。
 *
 * 解码、叠加与重编码交给 Media3 Transformer：它把每一帧走 GL 处理后再交给硬件编码器，
 * 比"解成 Bitmap 再自己写 MediaCodec"少两次颜色空间往返；音轨由它决定直接搬还是重编。
 * 这里只负责把参数翻成效果列表，以及把异步的 Transformer 收成一次同步等待。
 */
class VideoEffectEngine(
    private val context: Context,
    private val workspace: Workspace,
    private val images: ImageEngine,
) {

    fun transform(
        item: WorkItem,
        operation: Operation,
        subtitleFile: File?,
        onProgress: (Int) -> Unit = {},
    ): EngineOutput {
        require(item.kind == FileKind.Mp4 || item.kind == FileKind.WebM ||
            item.kind == FileKind.Mkv || item.kind == FileKind.QuickTime) { "这个文件不是视频" }
        val meta = videoDisplayMeta(item.file)
        val notes = ArrayList<String>()
        val effects: List<Effect>
        val tag: String
        when (operation) {
            is Operation.CropVideo -> {
                val crop = ImageGeometry.cropRect(operation.rect, meta.width, meta.height)
                    ?: error("裁哪块要写「左,上,宽,高」，四段也可以都带 %")
                notes += crop.notes
                val left = crop.rect.left.toFloat() / meta.width * 2f - 1f
                val right = crop.rect.right.toFloat() / meta.width * 2f - 1f
                val bottom = 1f - crop.rect.bottom.toFloat() / meta.height * 2f
                val top = 1f - crop.rect.top.toFloat() / meta.height * 2f
                effects = listOf(Crop(left, right, bottom, top))
                tag = "裁画面"
                notes += "裁出 ${crop.rect.right - crop.rect.left}×${crop.rect.bottom - crop.rect.top}"
            }
            is Operation.VideoWatermark -> {
                require(operation.text.trim().isNotEmpty()) { "先写要盖的水印文字" }
                val overlay = Bitmap.createBitmap(meta.width, meta.height, Bitmap.Config.ARGB_8888)
                overlay.eraseColor(Color.TRANSPARENT)
                images.drawTextWatermark(
                    overlay, operation.text.trim(), operation.spot, false,
                    operation.opacityPercent, 0, operation.sizePercent,
                )
                effects = listOf(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(overlay))))
                tag = "水印"
                notes += "「${operation.text.trim().take(12)}」· ${operation.opacityPercent.coerceIn(3, 100)}% 不透明"
            }
            is Operation.BurnSubtitles -> {
                val picked = operation.subtitleFrom.trim()
                val cues: List<Cue>
                if (picked.isEmpty()) {
                    // 不选外挂字幕就烧视频自带的那条轨：扫描/下载来的片子常常已经带着字幕
                    val embedded = embeddedCues(item.file)
                    cues = embedded.first
                    notes += "烧的是视频自带的字幕轨（${mimeLabel(embedded.second)}）"
                } else {
                    val file = subtitleFile ?: error("选的字幕「$picked」已经不在工作台里了，重新选一份")
                    val decoded = TextCodecs.decodeForConversion(file.readBytes(), null)
                    val (format, claimed) = Subtitles.detect(file.name, decoded.text)
                    cues = Subtitles.parse(format, decoded.text)
                    if (claimed != null && claimed != format) {
                        notes += "扩展名写的是 ${claimed.label}，按内容判为 ${format.label}"
                    }
                    notes += "${format.label} ${cues.size} 条"
                }
                require(cues.isNotEmpty()) { "字幕轨里一条正文都没解出来" }
                effects = listOf(
                    OverlayEffect(
                        listOf(
                            CueOverlay(
                                width = meta.width,
                                height = meta.height,
                                cues = cues,
                                sizePercent = operation.sizePercent,
                                bottomPercent = operation.bottomPercent,
                            ),
                        ),
                    ),
                )
                tag = "烧字幕"
            }
            else -> error("这个操作不需要改画面")
        }

        val output = workspace.newStagingFile("mp4")
        // Transformer 要自己新建文件；staging 已经先建了一个空壳，先删掉
        output.delete()
        val result = runTransformer(item.file, output, effects, onProgress)
        require(output.isFile && output.length() > 0) { "没编出可用的视频" }
        require(result.videoFrameCount > 0) {
            "一帧都没写进成品（编码器 ${result.videoEncoderName ?: "未知"}），没产出可用文件"
        }
        if (result.width > 0 && result.height > 0) notes.add(0, "${result.width}×${result.height}")
        notes += "${result.videoFrameCount} 帧 · ${"%.1f".format(result.durationMs / 1000.0)} 秒 · 重新编码"
        return EngineOutput(OutputNaming.tagged(item.name, tag, "mp4"), output, notes.joinToString(" · "))
    }

    /**
     * 导出视频自带的字幕轨。取样时间来自容器，正文按编码格式解（tx3g / WebVTT / SSA）。
     * 外挂字幕不走这里 —— 它们本来就已是 SRT / VTT / ASS 文件。
     */
    fun extractSubtitles(item: WorkItem): EngineOutput {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(item.file.absolutePath)
            val chosen = chooseTextTrack(extractor)
            requireNotNull(chosen) {
                "这个视频里没有文字字幕轨；外挂字幕直接就是 SRT / VTT / ASS，不需要导出"
            }
            extractor.selectTrack(chosen.index)
            val language = chosen.language
            val cues = readCues(extractor, chosen.mime)
            require(cues.isNotEmpty()) { "字幕轨有 ${chosen.count} 条，但一条正文都没解出来" }
            val output = workspace.newStagingFile("srt").apply {
                writeText(Subtitles.render(SubtitleFormat.Srt, cues), Charsets.UTF_8)
            }
            val notes = buildList {
                add("${cues.size} 条 · ${mimeLabel(chosen.mime)}")
                add("结束时间按下一句开始时补")
                if (!language.isNullOrBlank()) add("语言 $language")
                if (chosen.count > 1) add("共 ${chosen.count} 条字幕轨，取第一条")
            }
            return EngineOutput(
                OutputNaming.tagged(item.name, "字幕", "srt"),
                output,
                notes.joinToString(" · "),
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private class TrackChoice(val index: Int, val mime: String, val language: String?, val count: Int)

    private fun chooseTextTrack(extractor: MediaExtractor): TrackChoice? {
        var first: TrackChoice? = null
        var count = 0
        for (index in 0 until extractor.trackCount) {
            val format = runCatching { extractor.getTrackFormat(index) }.getOrNull() ?: continue
            val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull().orEmpty()
            if (!isTextSubtitle(mime)) continue
            count++
            if (first == null) {
                first = TrackChoice(
                    index,
                    mime,
                    runCatching { format.getString(MediaFormat.KEY_LANGUAGE) }.getOrNull(),
                    count,
                )
            }
        }
        return first?.let { TrackChoice(it.index, it.mime, it.language, count) }
    }

    /** 烧字幕不选外挂文件时用：把视频自带的那条文字字幕轨读成 cue。 */
    private fun embeddedCues(file: File): Triple<List<Cue>, String, String?> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val chosen = chooseTextTrack(extractor)
                ?: error("这个视频没有文字字幕轨；要么在工作台放一份字幕文件，要么换一条带字幕的片子")
            extractor.selectTrack(chosen.index)
            val cues = readCues(extractor, chosen.mime)
            return Triple(cues, chosen.mime, chosen.language)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun readCues(extractor: MediaExtractor, mime: String): List<Cue> {
        val info = android.media.MediaCodec.BufferInfo()
        val buffer = java.nio.ByteBuffer.allocate(1 shl 20)
        val samples = ArrayList<Pair<Long, String>>()
        var guard = 0
        while (guard++ < 200_000) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val startUs = extractor.sampleTime.coerceAtLeast(0)
            buffer.position(0)
            buffer.limit(size)
            val bytes = ByteArray(size).also { buffer.get(it) }
            val text = when {
                mime.contains("3gpp", ignoreCase = true) || mime.contains("tx3g", ignoreCase = true) ->
                    TimedText.decodeTx3g(bytes)
                mime.contains("vtt", ignoreCase = true) -> TimedText.decodeWebVttSample(bytes)
                mime.contains("ssa", ignoreCase = true) || mime.contains("ass", ignoreCase = true) ->
                    TimedText.decodeSsaSample(String(bytes, Charsets.UTF_8))
                else -> String(bytes, Charsets.UTF_8).replace("\u0000", "").trim()
            }
            extractor.advance()
            if (text.isNotBlank()) samples += startUs to text
        }
        // 容器不给每条采样的时长（MediaExtractor 没有 sampleDuration），结束时间按下一句开始时补；
        // 最后一句按 2 秒收尾。重叠字幕因此会被截短，导出说明里如实写。
        return samples.mapIndexed { index, (startUs, text) ->
            val nextUs = samples.getOrNull(index + 1)?.first ?: (startUs + 2_000_000)
            val endUs = if (nextUs > startUs) nextUs else startUs + 1_000
            Cue((startUs / 1000), max(startUs / 1000 + 1, endUs / 1000), text.split('\n'))
        }
    }

    private fun isTextSubtitle(mime: String): Boolean {
        val lower = mime.lowercase()
        if (lower.contains("ttml") || lower.contains("subtitle") || lower.contains("subrip") ||
            lower.contains("vtt") || lower.contains("ssa") || lower.contains("3gpp") ||
            lower.contains("tx3g")
        ) {
            return true
        }
        // 逐位图的字幕（DVD / PGS）没法变成文字，别把它当"有字幕轨"骗用户
        return false
    }

    private fun mimeLabel(mime: String): String = when {
        mime.contains("3gpp", true) || mime.contains("tx3g", true) -> "MP4 定时文本"
        mime.contains("vtt", true) -> "WebVTT"
        mime.contains("ssa", true) || mime.contains("ass", true) -> "ASS/SSA"
        mime.contains("subrip", true) -> "SRT"
        else -> mime
    }

    /** Transformer 必须在带 Looper 的线程上用；这里在专用线程上启动并等它结束。 */
    private fun runTransformer(
        input: File,
        output: File,
        effects: List<Effect>,
        onProgress: (Int) -> Unit,
    ): ExportResult {
        val thread = HandlerThread("fileforge-video-effect").apply { start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        var result: ExportResult? = null
        var failure: Throwable? = null
        try {
            handler.post {
                try {
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            result = exportResult
                            finished.set(true)
                            latch.countDown()
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            failure = exportException
                            finished.set(true)
                            latch.countDown()
                        }
                    }
                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setLooper(thread.looper)
                        .addListener(listener)
                        .build()
                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                        .setEffects(Effects(emptyList(), effects))
                        .build()
                    val holder = androidx.media3.transformer.ProgressHolder()
                    fun poll() {
                        if (finished.get()) return
                        val state = runCatching { transformer.getProgress(holder) }.getOrDefault(-1)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                        handler.postDelayed(::poll, 500)
                    }
                    transformer.start(edited, output.absolutePath)
                    handler.postDelayed(::poll, 500)
                } catch (error: Throwable) {
                    failure = error
                    latch.countDown()
                }
            }
            if (!latch.await(15, TimeUnit.MINUTES)) {
                handler.post { runCatching { /* Transformer 没有公开 release，超时就让它随线程结束 */ } }
                throw IllegalStateException("转码 15 分钟还没结束，先停下；可以裁短一点再试")
            }
            failure?.let { error ->
                val detail = (error as? ExportException)?.let { "${it.errorCodeName} ${it.message}" }
                    ?: error.message ?: error.javaClass.simpleName
                throw IllegalStateException("这台设备没能完成这段视频的处理：$detail", error)
            }
            return result ?: error("转码结束了却没有结果")
        } finally {
            runCatching { thread.quitSafely() }
        }
    }
}

/**
 * 一条字幕叠一帧：同一句字幕期间拿到的是同一个 Bitmap（Transformer 会复用纹理），
 * 换句才重画；没有字幕的帧给一张 1×1 透明图。
 */
private class CueOverlay(
    private val width: Int,
    private val height: Int,
    private val cues: List<Cue>,
    private val sizePercent: Int,
    private val bottomPercent: Int,
) : BitmapOverlay() {

    private val blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var shown: Cue? = null
    private var bitmap: Bitmap? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val cue = Subtitles.cueAt(cues, presentationTimeUs / 1000)
        if (cue == null) return blank
        if (cue !== shown) {
            bitmap?.recycle()
            bitmap = render(cue)
            shown = cue
        }
        return bitmap ?: blank
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        OverlaySettings.Builder().build()

    private fun render(cue: Cue): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(out)
        val fontSize = max(10f, height * 0.055f * sizePercent.coerceIn(20, 400) / 100f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = fontSize
            textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL
            setShadowLayer(fontSize / 8f, 0f, 0f, Color.argb(210, 0, 0, 0))
        }
        val stroke = Paint(fill).apply {
            color = Color.argb(220, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = max(2f, fontSize / 14f)
            clearShadowLayer()
        }
        val lineHeight = fontSize * 1.28f
        val baseline = height - height * bottomPercent.coerceIn(0, 45) / 100f -
            lineHeight * (cue.lines.size - 1)
        cue.lines.forEachIndexed { index, line ->
            val y = baseline + index * lineHeight
            canvas.drawText(line, width / 2f, y, stroke)
            canvas.drawText(line, width / 2f, y, fill)
        }
        return out
    }

    override fun release() {
        bitmap?.recycle()
        bitmap = null
        blank.recycle()
        super.release()
    }
}
