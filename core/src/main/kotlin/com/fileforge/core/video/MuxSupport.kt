package com.fileforge.core.video

import com.fileforge.core.ops.VideoFormat

/**
 * 每种封装能收哪些音轨。
 *
 * 无压缩 PCM（MP4 里的 sample entry 是 `twos` / `lpcm` / `ipcm`，MediaExtractor 报成
 * `audio/raw`）看着是"能播的音轨"，但 MediaMuxer 的 MP4 写手不收：真机上把这种文件
 * （不少运动相机/无人机的原始片段就是这样）原样搬进 muxer，`addTrack` 直接抛
 * "Failed to add the track to the muxer"，整件事白跑。所以先按 MIME 判一次，
 * 收不下的就只压画面并如实说明。
 */
object MuxSupport {

    private val MP4_AUDIO = setOf(
        "audio/mp4a-latm",
        "audio/mp4a-adts",
        "audio/aac",
        "audio/amr-wb",
        "audio/amr-nb",
        "audio/3gpp",
        "audio/flac",
        "audio/opus",
    )

    private val WEBM_AUDIO = setOf("audio/vorbis", "audio/opus")

    fun keepsAudio(container: VideoFormat, audioMime: String?): Boolean {
        if (audioMime.isNullOrBlank()) return false
        val allowed = if (container == VideoFormat.WebM) WEBM_AUDIO else MP4_AUDIO
        return allowed.contains(audioMime.trim().lowercase())
    }

    /** 收得下但 muxer 现场拒绝时也要能退回来，所以这里只解释"为什么不带音轨了"。 */
    fun dropReason(audioMime: String?): String = when {
        audioMime.isNullOrBlank() -> "读不到音轨格式"
        audioMime.startsWith("audio/raw") || audioMime.contains("pcm") -> "音轨是无压缩 PCM，这个封装收不下"
        else -> "这个封装收不下 $audioMime 音轨"
    }
}
