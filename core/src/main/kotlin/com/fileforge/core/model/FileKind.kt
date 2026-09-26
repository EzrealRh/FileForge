package com.fileforge.core.model

import com.fileforge.core.doc.Html

/** 文件真实类型，只看头部魔数，不信扩展名。 */
enum class FileKind {
    Pdf, Png, Jpeg, Gif, WebP, Bmp, Heic, Avif, Mp4, WebM, Mkv, QuickTime,
    Mp3, Aac, M4a, Flac, Ogg, Wav,
    Zip, Ico, Docx, Xlsx, Pptx, Epub, Tar, Gzip, Text, Html, Unknown;

    val isImage: Boolean get() = this in IMAGE_KINDS
    val isVideo: Boolean get() = this in VIDEO_KINDS
    val isAudio: Boolean get() = this in AUDIO_KINDS

    /**
     * 能不能当文本处理。网页单独占一类只为把角标和 mime 说对，
     * 可用的操作跟纯文本是同一套 —— 很多人把网页存成 .txt，两边都得能抽文字。
     */
    val isTextual: Boolean get() = this == Text || this == Html

    /** 分享和写 MediaStore 都要用；认不出的一律按二进制流给。 */
    val mimeType: String get() = when (this) {
        Pdf -> "application/pdf"
        Png -> "image/png"
        Jpeg -> "image/jpeg"
        Gif -> "image/gif"
        Ico -> "image/x-icon"
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
        Docx -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        Xlsx -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        Epub -> "application/epub+zip"
        Tar -> "application/x-tar"
        Gzip -> "application/gzip"
        Pptx -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        Text -> "text/plain"
        Html -> "text/html"
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
        Ico -> "ICO"
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
        Docx -> "DOCX"
        Xlsx -> "XLSX"
        Epub -> "EPUB"
        Tar -> "TAR"
        Gzip -> "GZ"
        Pptx -> "PPTX"
        Text -> "文本"
        Html -> "HTML"
        Unknown -> "文件"
    }

    companion object {
        val IMAGE_KINDS = setOf(Png, Jpeg, Gif, WebP, Bmp, Heic, Avif, Ico)
        val VIDEO_KINDS = setOf(Mp4, WebM, Mkv, QuickTime)

        /**
         * OGG 容器里可能是 vorbis 也可能是 opus，前 64 字节分不出来（要看头页才知道），
         * 所以这里只到容器为止 —— 真编码由 MediaExtractor 报，不靠猜。
         */
        val AUDIO_KINDS = setOf(Mp3, Aac, M4a, Flac, Ogg, Wav)
    }
}

object FileTypeSniffer {

    /** 绝大多数类型看前几十字节就够；tar 的 `ustar` 在第 257 字节，所以要给到 512 字节。 */
    fun sniff(header: ByteArray): FileKind {
        if (header.size < 12) return FileKind.Unknown

        fun u(i: Int) = header[i].toInt() and 0xFF
        fun isAscii(at: Int, text: String): Boolean {
            if (header.size < at + text.length) return false
            return text.indices.all { offset -> u(at + offset) == text[offset].code }
        }
        /** 只在开头这么多字节里找子串：EBML 的 DocType 就在前面，扫太远会撞上正文里的同样字节。 */
        val probe = minOf(header.size, PROBE)
        fun contains(text: String): Boolean =
            (0..probe - text.length).any { isAscii(it, text) }

        return when {
            isAscii(0, "%PDF") -> FileKind.Pdf
            u(0) == 0x89 && isAscii(1, "PNG") -> FileKind.Png
            u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> FileKind.Jpeg
            isAscii(0, "GIF8") -> FileKind.Gif
            // ICO：保留字 0、类型 1(图标)/2(光标)，条目数不为 0。只认前四条约束——
            // 光看 00 00 01 00 会撞上别的二进制头，所以再加一条"数据都落在文件内"
            u(0) == 0 && u(1) == 0 && u(2) == 1 && u(3) == 0 &&
                u(4) in 1..255 && u(5) == 0 && looksLikeIcoDirectory(header) -> FileKind.Ico
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
            // gzip 的两位魔数；里面装的是 tar 还是单个文件，由解包那边看过第一块再说
            u(0) == 0x1F && u(1) == 0x8B -> FileKind.Gzip
            // tar 没有开头的魔数：名字落在第 257 字节的 ustar 上，还要头块自己的校验和对得上
            com.fileforge.core.archive.Tar.looksLikeTar(header) -> FileKind.Tar
            looksLikeHtml(header) -> FileKind.Html
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

    /**
     * 网页没有魔数可看，只能认标记：第一个实义字符是 `<`，且开头这一截里出现常见的容器标签。
     *
     * 判据宁紧勿松：`<?xml` 开头直接不算（XML 是另一族，svg 也长这样），普通文本更不该被标成 HTML。
     * 真网页被当成纯文本没什么损失 —— "网页抽文字"两条操作对纯文本同样开放，引擎自己会判有没有标记。
     */
    fun looksLikeHtml(header: ByteArray): Boolean {
        var at = if (header.size >= 3 && (header[0].toInt() and 0xFF) == 0xEF &&
            (header[1].toInt() and 0xFF) == 0xBB && (header[2].toInt() and 0xFF) == 0xBF
        ) 3 else 0                                                      // UTF-8 BOM 先跳掉
        while (at < header.size && (header[at].toInt() and 0xFF) <= 0x20) at++
        if (at >= header.size || header[at] != '<'.code.toByte()) return false
        // XML 声明开头的是另一族（svg 也长这样），别抢它的角标
        if (String(header, at, 5, Charsets.ISO_8859_1) == "<?xml") return false
        return Html.looksLikeHtml(String(header, at, header.size - at, Charsets.ISO_8859_1))
    }

    /**
     * 目录里每一项都得说得通才算图标。
     *
     * 只判"偏移在目录之后、长度是正数"这一条：嗅探用的头只有几十到几百字节，
     * 拿它去比图片数据的偏移会不会越界是没有意义的（真图标必然超出），所以越界检查
     * 留给真正读文件时的 Ico.directory 去做。这里要挡的是"开头碰巧是 00 00 01 00"的别的文件。
     */
    private fun looksLikeIcoDirectory(header: ByteArray): Boolean {
        val count = (header[4].toInt() and 0xFF) or ((header[5].toInt() and 0xFF) shl 8)
        if (count == 0 || count > 64) return false
        return (0 until count).all { index ->
            val at = 6 + index * 16
            if (at + 16 > header.size) return false
            val size = (header[at + 8].toInt() and 0xFF) or ((header[at + 9].toInt() and 0xFF) shl 8) or
                ((header[at + 10].toInt() and 0xFF) shl 16) or ((header[at + 11].toInt() and 0xFF) shl 24)
            val offset = (header[at + 12].toInt() and 0xFF) or ((header[at + 13].toInt() and 0xFF) shl 8) or
                ((header[at + 14].toInt() and 0xFF) shl 16) or ((header[at + 15].toInt() and 0xFF) shl 24)
            size in 1..(1 shl 26) && offset >= 6 + count * 16
        }
    }

    /** [contains] 那条子串判据只在开头这么多字节里找。 */
    private const val PROBE = 64

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
