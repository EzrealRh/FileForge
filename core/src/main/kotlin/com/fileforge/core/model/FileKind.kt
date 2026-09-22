package com.fileforge.core.model

/** 文件真实类型，只看头部魔数，不信扩展名。 */
enum class FileKind {
    Pdf, Png, Jpeg, Gif, WebP, Bmp, Heic, Avif, Mp4, WebM, Mkv, QuickTime, Zip, Unknown;

    val isImage: Boolean get() = this in IMAGE_KINDS
    val isVideo: Boolean get() = this in VIDEO_KINDS

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
        Zip -> "application/zip"
        Unknown -> "application/octet-stream"
    }

    companion object {
        val IMAGE_KINDS = setOf(Png, Jpeg, Gif, WebP, Bmp, Heic, Avif)
        val VIDEO_KINDS = setOf(Mp4, WebM, Mkv, QuickTime)
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
            u(0) == 0x50 && u(1) == 0x4B && u(2) == 0x03 && u(3) == 0x04 -> FileKind.Zip
            else -> FileKind.Unknown
        }
    }

    private fun isoBrandToKind(brand: String) = when {
        brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("heim") ||
            brand.startsWith("heis") || brand.startsWith("mif1") || brand.startsWith("msf1") -> FileKind.Heic
        brand.startsWith("avif") || brand.startsWith("avis") -> FileKind.Avif
        brand.startsWith("qt  ") || brand.startsWith("qt") -> FileKind.QuickTime
        else -> FileKind.Mp4
    }
}
