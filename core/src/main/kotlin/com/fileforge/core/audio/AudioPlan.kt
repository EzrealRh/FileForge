package com.fileforge.core.audio

import com.fileforge.core.naming.OutputNaming

/**
 * 音频的落点。`muxed=false` 的那个不经过 MediaMuxer —— 安卓没有 WAV 写手，
 * 得自己拼 RIFF 头，所以引擎走的是两条完全不同的路，别在引擎里用 if 分。
 */
enum class AudioTarget(val label: String, val extension: String, val mime: String, val muxed: Boolean) {

    M4a("M4A（AAC）", "m4a", "audio/mp4", true),
    Wav("WAV（无损）", "wav", "audio/wav", false),
    ;

    companion object {
        fun ofExtension(ext: String): AudioTarget? =
            entries.firstOrNull { it.extension.equals(ext.trim(), ignoreCase = true) }
    }
}

/**
 * 转音频的判据全收在这里，引擎只做胶水。
 *
 * 为什么不让引擎自己决定"能不能原样搬"：上一版视频压缩的两个必现缺陷 ——
 * ① 只查轨不选轨，于是解码器一帧都拿不到、写出几 KB 空壳却报成功；
 * ② 无压缩 PCM 音轨被 muxer 拒收，整件事直接失败 ——
 * 都是这类"看起来是引擎细节"的判断留在引擎里、于是只能在真机上才暴露出来的结果。
 * 现在它们在这里有单测，真机之前就能被判死。
 */
object AudioPlan {

    /** AAC 家族：本来就是 MP4 容器认的 sample entry，可以原样搬进去不重编。 */
    private val AAC = setOf("audio/mp4a-latm", "audio/mp4a-adts", "audio/aac", "audio/mp4a")

    /** 安卓侧能稳妥编出来的只有 AAC（没有 MP3 编码器，也别指望它），所以这不是一个可以无限加的列表。 */
    const val DEFAULT_KBPS = 192
    const val MIN_KBPS = 64
    const val MAX_KBPS = 320

    /** 干净 MIME：MediaExtractor 偶尔报带参数或大小写不一致的串。 */
    private fun norm(mime: String?): String? = mime?.trim()?.lowercase()?.substringBefore(';')?.trim()

    /**
     * 原样搬运只在"AAC 进 m4a"这一条上成立。
     * MP3 特别容易被误当成"也是现成的压缩音频"：它技术上能塞进 MP4 的 audio sample entry
     * （ISO-14496-3 的 object type 0x6B），但安卓的 muxer 和各家解码器对这条支持不一致，
     * 所以一律走解码重编 —— 慢一点，但产物在哪台机上都能放。
     */
    fun canPassthrough(sourceMime: String?, target: AudioTarget): Boolean {
        val mime = norm(sourceMime) ?: return false
        return target == AudioTarget.M4a && mime in AAC
    }

    /**
     * 安卓有没有这条音轨的解码器。列表按 `MediaFormat.MIMETYPE_AUDIO_*` 里系统真带的挑，
     * WMA/APE/ALAC 这类不在里面 —— 遇到它们要在**动手之前**就说清做不了，
     * 而不是起一个解不出样本的解码器、跑完再产出一个空文件骗人。
     */
    fun decodableMime(mime: String?): Boolean {
        val m = norm(mime) ?: return false
        return m in AAC || m in DECODABLE
    }

    private val DECODABLE = setOf(
        "audio/mpeg", "audio/mpeg2", "audio/mp3",
        "audio/flac", "audio/opus", "audio/vorbis", "audio/mp4a-latm", "audio/raw",
        "audio/wav", "audio/x-wav", "audio/amr-wb", "audio/amr-nb", "audio/3gpp",
        "audio/g711-alaw", "audio/g711-mlaw", "audio/msgsm", "audio/qcelp", "audio/alac",
    )

    /**
     * 挑要抽的音轨：返回第一条 MIME 以 audio 开头的轨的下标，没有就 -1。
     * 只返回下标不返回布尔，是因为调用方必须拿这个下标去 selectTrack ——
     * 视频的缺陷①就是"查到了轨却按 0 号轨去解"，那样解出来的是视频轨，一帧音频都没有。
     */
    fun pickAudioTrack(mimes: List<String?>): Int =
        mimes.indexOfFirst { norm(it)?.startsWith("audio/") == true }

    /** 视频里到底有没有声音 —— 用来在动手之前就告诉用户"这条没音轨"，而不是产出一个空文件。 */
    fun hasAudioTrack(mimes: List<String?>): Boolean = pickAudioTrack(mimes) >= 0

    /**
     * 按目标体积反推码率（kbps）。`durationUs` 用微秒，和 MediaExtractor 一致。
     * 时长拿不到就不反推（跟着源或走默认档），因为反推出一个基于错时长的码率比不反推更坏。
     */
    fun bitrateKbps(targetBytes: Long?, durationUs: Long, sourceKbps: Int?): Int {
        val fromTarget = if (targetBytes != null && targetBytes > 0 && durationUs > 0)
            (targetBytes * 8_000 / durationUs).toInt() else 0
        val wanted = when {
            fromTarget > 0 -> fromTarget
            sourceKbps != null && sourceKbps > 0 -> sourceKbps
            else -> DEFAULT_KBPS
        }
        return wanted.coerceIn(MIN_KBPS, MAX_KBPS)
    }

    /**
     * 成功判据：**真的搬过/编过样本**才算成。
     * "产物字节数 > 0"是假判据 —— muxer 一 start 就会写文件头和 moov，空壳也有几 KB。
     */
    fun succeeded(samplesWritten: Int): Boolean = samplesWritten > 0

    /** 转格式保持原名换扩展名；从视频里提声音要标出来，否则同一个视频目录下会出现同名混淆。 */
    fun outputName(sourceName: String, target: AudioTarget, fromVideo: Boolean): String {
        val stem = OutputNaming.stem(sourceName)
        return if (fromVideo) OutputNaming.tagged(stem, "音频", target.extension)
        else "$stem.${target.extension}"
    }
}
