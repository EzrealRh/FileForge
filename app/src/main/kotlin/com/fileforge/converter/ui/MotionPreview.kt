package com.fileforge.converter.ui

import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fileforge.core.model.FileKind
import com.fileforge.converter.data.WorkItem
import java.io.File

/**
 * 会动的预览：GIF / 动图 WebP 交给系统解码器循环播，视频用 MediaPlayer 打到 TextureView 上。
 * 不支持动的类型走 [fallback]，也就是原来那张静帧预览图。
 */
@Composable
fun MotionPreview(item: WorkItem, modifier: Modifier, fallback: @Composable () -> Unit) {
    when {
        item.kind.isVideo -> VideoPreview(item.file, modifier)
        item.kind == FileKind.Gif || item.kind == FileKind.WebP -> AnimatedImagePreview(item.file, modifier, fallback)
        else -> fallback()
    }
}

/** AnimatedImageDrawable 要 API 28，更早的系统退回静帧；这只影响预览，转换本身不受影响。 */
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
