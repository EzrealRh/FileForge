package com.fileforge.converter.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fileforge.core.model.FileKind
import com.fileforge.core.gif.GifDecoder
import com.fileforge.converter.data.WorkItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** 预览只解码这么多总像素就停，免得为一个动图吃几十兆堆。 */
private const val PREVIEW_PIXEL_BUDGET = 10_000_000

/**
 * 会动的预览：GIF 用自家 `:core` 解码器按帧自己放，动图 WebP 交给系统 `ImageDecoder`，
 * 视频用 MediaPlayer 打到 TextureView 上。不支持动的类型走 [fallback]，也就是原来那张静帧预览图。
 *
 * GIF 之所以不走系统解码器：真机上 `AnimatedImageDrawable` 放着放着就不动了（文件本身是好的，
 * Pillow 复核过 29 帧、每帧 100ms、无限循环），自家解码 + 自家计时反而可控、还能兼顾 8.x。
 */
@Composable
fun MotionPreview(item: WorkItem, modifier: Modifier, fallback: @Composable () -> Unit) {
    when {
        item.kind.isVideo -> VideoPreview(item.file, modifier)
        item.kind == FileKind.Gif -> GifPreview(item.file, modifier, fallback)
        item.kind == FileKind.WebP -> AnimatedImagePreview(item.file, modifier, fallback)
        else -> fallback()
    }
}

private class GifClip(
    val width: Int,
    val height: Int,
    val frames: List<IntArray>,
    val delaysMs: List<Long>,
    val truncated: Boolean,
)

@Composable
private fun GifPreview(file: File, modifier: Modifier, fallback: @Composable () -> Unit) {
    var clip by remember(file) { mutableStateOf<GifClip?>(null) }
    var broken by remember(file) { mutableStateOf(false) }

    LaunchedEffect(file) {
        val decoded = withContext(Dispatchers.Default) {
            runCatching {
                val image = GifDecoder.decode(file.readBytes(), pixelBudget = PREVIEW_PIXEL_BUDGET)
                if (image.frames.isEmpty()) null
                else GifClip(
                    width = image.width,
                    height = image.height,
                    frames = image.frames.map { it.argb },
                    delaysMs = image.frames.map { (it.delayCs * 10L).coerceAtLeast(20L) },
                    truncated = image.truncated,
                )
            }.getOrNull()
        }
        if (decoded == null) broken = true else clip = decoded
    }

    val current = clip
    if (current == null) {
        if (broken) fallback() else Text("正在解码动画…", style = MaterialTheme.typography.bodySmall)
        return
    }

    val bitmap = remember(current) { Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888) }
    var tick by remember(current) { mutableIntStateOf(0) }
    DisposableEffect(current) {
        onDispose { bitmap.recycle() }
    }
    LaunchedEffect(current) {
        var index = 0
        while (true) {
            bitmap.setPixels(current.frames[index], 0, current.width, 0, 0, current.width, current.height)
            tick++
            delay(current.delaysMs[index])
            index = (index + 1) % current.frames.size
        }
    }
    Box(modifier) {
        key(tick) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = file.name,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            "${current.frames.size} 帧在放" + if (current.truncated) "（帧太多，只播前面这些）" else " · 循环",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
    }
}

/** AnimatedImageDrawable 要 API 28，更早的系统退回静帧；只影响动图 WebP 的预览。 */
@Composable
private fun AnimatedImagePreview(file: File, modifier: Modifier, fallback: @Composable () -> Unit) {
    val drawable = remember(file) {
        if (Build.VERSION.SDK_INT < 28) null
        else runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) }.getOrNull()
    }
    if (drawable == null) {
        fallback()
        return
    }
    DisposableEffect(drawable) {
        val animation = drawable as? AnimatedImageDrawable
        animation?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        animation?.start()
        onDispose { animation?.stop() }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { view -> view.setImageDrawable(drawable) },
    )
}

@Composable
private fun VideoPreview(file: File, modifier: Modifier) {
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var paused by remember(file) { mutableStateOf(false) }
    var note by remember(file) { mutableStateOf("正在准备…") }

    DisposableEffect(file) {
        onDispose {
            player?.releaseQuietly()
            player = null
        }
    }

    Box(modifier.pointerInput(file) {
        detectTapGestures {
            val media = player ?: return@detectTapGestures
            runCatching {
                if (media.isPlaying) {
                    media.pause()
                    paused = true
                } else {
                    media.start()
                    paused = false
                }
            }
        }
    }) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            player?.releaseQuietly()
                            val media = MediaPlayer()
                            player = media
                            runCatching {
                                media.setDataSource(file.absolutePath)
                                media.setSurface(Surface(texture))
                                media.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                                media.setLooping(true)
                                media.setOnPreparedListener {
                                    it.start()
                                    note = ""
                                }
                                media.setOnErrorListener { _, _, _ ->
                                    note = "这台设备放不了这个视频（转换不受影响）"
                                    true
                                }
                                media.prepareAsync()
                            }.onFailure { note = "这个视频播不了：${it.message ?: it.javaClass.simpleName}" }
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            player?.releaseQuietly()
                            player = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
        )
        Text(
            if (paused) "已暂停，点一下继续" else note.ifBlank { "循环播放 · 点画面暂停" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.45f)),
        )
    }
}

private fun MediaPlayer.releaseQuietly() {
    runCatching { if (isPlaying) stop() }
    runCatching { release() }
}
