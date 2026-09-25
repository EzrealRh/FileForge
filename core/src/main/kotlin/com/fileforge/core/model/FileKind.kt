package com.fileforge.core.model

/** 文件真实类型，只看头部魔数，不信扩展名。 */
enum class FileKind {
    Pdf, Png, Jpeg, Gif, WebP, Bmp, Heic, Avif, Mp4, WebM, Mkv, QuickTime,
    Mp3, Aac, M4a, Flac, Ogg, Wav,
    Zip, Text, Unknown;

    val isImage: Boolean get() = this in IMAGE_KINDS
    val isVideo: Boolean get() = this in VIDEO_KINDS
    val isAudio: Boolean get() = this in AUDIO_KINDS

    /** 分享和写 MediaStore 都要用；认不出的一律按二进制流给。 */
    val mimeType: String get() = when (this) {
        Pdf -> "application/pdf"
        Png -> "image/png"
        Jpeg -> "image/jpeg"
        Gif -> "image/gif"
        WebP -> "image/webp"
        Bmp -> "image/bmp"
        Heic -> "image/heic"
        Avif -> "image/avif"
        Mp4 -> "video/mp4"
        WebM -> "video/webm"
        Mkv -> "video/x-matroska"
        QuickTime -> "video/quicktime"
        Mp3 -> "audio/mpeg"
        Aac -> "audio/aac"
        M4a -> "audio/mp4"
        Flac -> "audio/flac"
        Ogg -> "audio/ogg"
        Wav -> "audio/wav"
        Zip -> "application/zip"
        Text -> "text/plain"
        Unknown -> "application/octet-stream"
    }

    /**
     * 界面角标用的短名。以前两处各抄了一张表（操作面板和工作台卡片），
     * 新增一类就得同步改两处，漏一处就把 MP3 标成"图片" —— 收在这里只留一份。
     */
    val badge: String get() = when (this) {
        Pdf -> "PDF"
        Png -> "PNG"
        Jpeg -> "JPEG"
        Gif -> "GIF"
        WebP -> "WebP"
        Bmp -> "BMP"
        Heic -> "HEIC"
        Avif -> "AVIF"
        Mp4 -> "MP4"
        WebM -> "WebM"
        Mkv -> "MKV"
        QuickTime -> "MOV"
        Mp3 -> "MP3"
        Aac -> "AAC"
        M4a -> "M4A"
        Flac -> "FLAC"
        Ogg -> "OGG"
        Wav -> "WAV"
        Zip -> "ZIP"
        Text -> "文本"
        Unknown -> "文件"
    }

    companion object {
        val IMAGE_KINDS = setOf(Png, Jpeg, Gif, WebP, Bmp, Heic, Avif)
        val VIDEO_KINDS = setOf(Mp4, WebM, Mkv, QuickTime)

        /**
         * OGG 容器里可能是 vorbis 也可能是 opus，前 64 字节分不出来（要看头页才知道），
         * 所以这里只到容器为止 —— 真编码由 MediaExtractor 报，不靠猜。
         */
        val AUDIO_KINDS = setOf(Mp3, Aac, M4a, Flac, Ogg, Wav)
    }
}

object FileTypeSniffer {

    /** 读文件前 64 字节足够判定这里用到的所有类型。 */
    fun sniff(header: ByteArray): FileKind {
        if (header.size < 12) return FileKind.Unknown

        fun u(i: Int) = header[i].toInt() and 0xFF
        fun isAscii(at: Int, text: String): Boolean {
            if (header.size < at + text.length) return false
            return text.indices.all { offset -> u(at + offset) == text[offset].code }
        }
        fun contains(text: String): Boolean =
            (0..header.size - text.length).any { isAscii(it, text) }

        return when {
            isAscii(0, "%PDF") -> FileKind.Pdf
            u(0) == 0x89 && isAscii(1, "PNG") -> FileKind.Png
            u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> FileKind.Jpeg
            isAscii(0, "GIF8") -> FileKind.Gif
            u(0) == 0x42 && u(1) == 0x4D -> FileKind.Bmp
            isAscii(0, "RIFF") && isAscii(8, "WEBP") -> FileKind.WebP
            // EBML：DocType 里写 webm 才是 WebM，否则是 Matroska
            u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 ->
                if (contains("webm")) FileKind.WebM else FileKind.Mkv
            u(0) == 0x00 && u(1) == 0x00 && u(2) == 0x01 && (u(3) == 0xBA || u(3) == 0xB3) -> FileKind.Mp4
            isAscii(4, "ftyp") -> isoBrandToKind(String(header, 8, 4, Charsets.ISO_8859_1))
            // 音频：ID3 标签在帧前面，必须先于裸帧同步字判，否则带标签的 mp3 会被当成"认不出"
            isAscii(0, "ID3") -> FileKind.Mp3
            isAscii(0, "fLaC") -> FileKind.Flac
            isAscii(0, "OggS") -> FileKind.Ogg
            u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46 && isAscii(8, "WAVE") -> FileKind.Wav
            // 裸帧同步 0x111：ADTS(AAC) 的 layer 两位恒为 0，MP3 不是 —— 靠这一位分开
            u(0) == 0xFF && (u(1) and 0xE0) == 0xE0 && (u(1) and 0xF6) == 0xF0 -> FileKind.Aac
            u(0) == 0xFF && (u(1) and 0xE0) == 0xE0 -> FileKind.Mp3
            u(0) == 0x50 && u(1) == 0x4B && u(2) == 0x03 && u(3) == 0x04 -> FileKind.Zip
            looksLikeText(header) -> FileKind.Text
            else -> FileKind.Unknown
        }
    }

    /**
     * 纯文本判据：**没有 NUL 字节**，且控制字符占比极低。
     *
     * 不用"能不能按 UTF-8 解码"来判 —— GBK 的中文按 UTF-8 解是失败的，那样
     * 最需要转编码的文件反而认不出来。NUL 是二进制最稳定的特征（`file`、git 都这么判）。
     * 文本类文件（txt/md/log/csv/json/srt/vtt/lrc/ass）都归到这里，具体是哪种
     * 由扩展名和各解析器自己说，不在类型层猜。
     */
    fun looksLikeText(header: ByteArray): Boolean {
        if (header.isEmpty()) return false
        var controls = 0
        for (b in header) {
            val v = b.toInt() and 0xFF
            if (v == 0) return false
            // 制表、换行、垂直制表、换页、回车是正常文本里会出现的控制字符，其余记一笔
            if (v < 0x20 && v != 0x09 && v != 0x0A && v != 0x0B && v != 0x0C && v != 0x0D) controls++
        }
        return controls * 20 <= header.size
    }

    private fun isoBrandToKind(brand: String) = when {
        brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("heim") ||
            brand.startsWith("heis") || brand.startsWith("mif1") || brand.startsWith("msf1") -> FileKind.Heic
        brand.startsWith("avif") || brand.startsWith("avis") -> FileKind.Avif
        // M4A 也是 ISO-BMFF，品牌名里带 m4a 才是音频 —— 以前一律归成 Mp4，
        // 于是"提取音频""转格式"这些操作在纯音频文件上全按视频处理
        brand.startsWith("M4A") || brand.startsWith("m4a") -> FileKind.M4a
        brand.startsWith("qt  ") || brand.startsWith("qt") -> FileKind.QuickTime
        else -> FileKind.Mp4
    }
}
