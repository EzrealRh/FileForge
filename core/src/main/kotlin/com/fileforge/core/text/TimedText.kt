package com.fileforge.core.text

/**
 * 容器里字幕采样的文字解码。
 *
 * 字幕轨的"一条采样"不是一整段字幕文件，而是一格文字：MP4 的 tx3g 前面带 2 字节长度，
 * WebVTT 包在 `vttc/payl` 盒子里，MKV 的 SSA 可能只给正文、也可能给一整行 `Dialogue:`。
 * 解错的表现是字幕里混进长度字节或 9 个字段名 —— 播放器不会报错，只会显示乱码。
 */
object TimedText {

    /** MP4 / 3GPP 定时文本：2 字节大端长度 + 正文（有 BOM 走 UTF-16BE，否则 UTF-8）。 */
    fun decodeTx3g(sample: ByteArray): String {
        if (sample.size < 2) return ""
        val length = ((sample[0].toInt() and 0xFF) shl 8) or (sample[1].toInt() and 0xFF)
        if (length <= 0 || sample.size <= 2) return ""
        val end = minOf(sample.size, 2 + length)
        val payload = sample.copyOfRange(2, end)
        val text = if (payload.size >= 2 && payload[0] == 0xFE.toByte() && payload[1] == 0xFF.toByte()) {
            String(payload, 2, payload.size - 2, Charsets.UTF_16BE)
        } else {
            String(payload, Charsets.UTF_8)
        }
        return clean(text)
    }

    /** MP4 里的 WebVTT：`vttc` 盒子套 `payl` 盒子，正文在 payl 里；嵌套按盒长走，不按固定偏移。 */
    fun decodeWebVttSample(sample: ByteArray): String = clean(webVttBox(sample, 0, sample.size))

    private fun webVttBox(data: ByteArray, start: Int, end: Int): String {
        var pos = start
        while (pos + 8 <= end) {
            val size = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            if (size < 8) return ""
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            val boxEnd = minOf(end, pos + size)
            if (type == "payl") return String(data, pos + 8, boxEnd - (pos + 8), Charsets.UTF_8)
            if (type == "vttc") return webVttBox(data, pos + 8, boxEnd)
            pos = boxEnd
        }
        return ""
    }

    /** MKV 的 SSA 采样：多数只给正文；也有的把一整行 `Dialogue: 0,0,...,正文` 原样塞进来。 */
    fun decodeSsaSample(sample: String): String {
        val text = sample.trim()
        if (text.isEmpty()) return ""
        val body = if (text.startsWith("Dialogue:", ignoreCase = true)) {
            val fields = text.substringAfter(':').split(',')
            if (fields.size >= 10) fields.drop(9).joinToString(",") else text
        } else {
            val fields = text.split(',')
            if (fields.size >= 10 && fields[0].trim().toIntOrNull() != null) {
                fields.drop(9).joinToString(",")
            } else {
                text
            }
        }
        return clean(body.replace("\\N", "\n").replace("\\n", "\n"))
    }

    private fun clean(text: String): String =
        text.replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n').trim()
}
