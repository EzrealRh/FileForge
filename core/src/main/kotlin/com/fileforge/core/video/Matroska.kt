package com.fileforge.core.video

import com.fileforge.core.text.Cue
import kotlin.math.max

/**
 * Matroska 里的文字字幕轨：系统 `MediaExtractor` 在不少安卓版本上不把 MKV 字幕轨列出来，
 * 所以这一条自己按 EBML 走：Tracks 里找 `S_TEXT/UTF8` 或 `S_TEXT/ASS`，Cluster 的
 * SimpleBlock / BlockGroup 取每格正文与时间。图片字幕（PGS/VobSub）明确跳过。
 *
 * 只认有长度的元素：流式录制的 MKV 会把 Segment / Cluster 写成"未知长度"，那种要按下一个
 * 一级元素反推边界，这里不做，遇到会说清楚（不会把后面的内容当字幕读）。
 */
object Matroska {

    class Subtitle(
        val codec: String,
        val language: String?,
        val cues: List<Cue>,
        val notes: List<String>,
    )

    private const val SEGMENT = 0x18538067L
    private const val INFO = 0x1549A966L
    private const val TIMECODE_SCALE = 0x2AD7B1L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val TRACK_TYPE = 0x83L
    private const val CODEC_ID = 0x86L
    private const val LANGUAGE = 0x22B59CL
    private const val CLUSTER = 0x1F43B675L
    private const val CLUSTER_TIMECODE = 0xE7L
    private const val SIMPLE_BLOCK = 0xA3L
    private const val BLOCK_GROUP = 0xA0L
    private const val BLOCK = 0xA1L
    private const val BLOCK_DURATION = 0x9BL

    fun readSubtitles(bytes: ByteArray): Subtitle? {
        val reader = Reader(bytes)
        val notes = ArrayList<String>()
        val state = State()

        // 顶层先跳过 EBML 头；Segment 的元素要**进去**读，不能像别的块一样整块跳过
        while (reader.hasMore()) {
            val id = reader.readId() ?: break
            val size = reader.readSize()
            if (id == SEGMENT) {
                val end = if (size < 0) bytes.size
                else (reader.pos + size).coerceAtMost(bytes.size.toLong()).toInt()
                parseSegment(reader, end, state, notes)
                reader.pos = end
                break
            }
            if (size < 0) break
            reader.pos = (reader.pos + size).coerceAtMost(bytes.size.toLong()).toInt()
        }

        val track = state.track ?: return null
        val sorted = state.raw.sortedBy { it.startMs }
        val cues = sorted.mapIndexed { index, cue ->
            val endMs = cue.durationMs?.let { cue.startMs + it }
                ?: sorted.getOrNull(index + 1)?.startMs
                ?: (cue.startMs + 2_000)
            Cue(cue.startMs, max(cue.startMs + 1, endMs), cue.text.split('\n'))
        }.filter { it.lines.any { line -> line.isNotBlank() } }
        return Subtitle(track.codec, track.language, cues, notes)
    }

    /** ASS 在 Matroska 里的正文是 ReadOrder, Layer, Style, Name, MarginL/R/V, Effect, Text（9 段）。 */
    fun decodeAssPayload(payload: String): String {
        val cleaned = payload.replace(Regex("\\{[^}]*}"), "").trim()
        val fields = cleaned.split(',')
        val body = when {
            fields.size >= 10 && fields[0].trim().toIntOrNull() != null -> fields.drop(9).joinToString(",")
            fields.size >= 9 && fields[0].trim().toIntOrNull() != null -> fields.drop(8).joinToString(",")
            else -> cleaned
        }
        return body.replace("\\N", "\n").replace("\\n", "\n").trim()
    }

    private class Track(val number: Long, val type: Long, val codec: String, val language: String?)

    private class RawCue(val startMs: Long, val text: String, val durationMs: Long?)

    private class State {
        var scale = 1_000_000L
        var track: Track? = null
        val raw = ArrayList<RawCue>()
    }

    private fun parseSegment(reader: Reader, end: Int, state: State, notes: ArrayList<String>) {
        while (reader.pos < end) {
            val id = reader.readId() ?: break
            val size = reader.readSize()
            if (size < 0) {
                notes += "MKV 里有未知长度的元素（流式录制常见），先重封装成有长度的文件再试"
                break
            }
            val elementEnd = (reader.pos + size).coerceAtMost(end.toLong()).toInt()
            when (id) {
                INFO -> state.scale = readScale(reader, elementEnd, state.scale)
                TRACKS -> readTracks(reader, elementEnd, notes)?.let { state.track = it }
                CLUSTER -> readCluster(reader, elementEnd, state.track, state.scale, state.raw)
            }
            reader.pos = elementEnd
        }
    }

    private fun readScale(reader: Reader, end: Int, fallback: Long): Long {
        var scale = fallback
        while (reader.pos < end) {
            val id = reader.readId() ?: break
            val size = reader.readSize()
            if (size < 0) break
            val valueEnd = limitOf(reader, size, end)
            if (id == TIMECODE_SCALE && size in 1..8) scale = reader.readUnsigned(size.toInt())
            reader.pos = valueEnd
        }
        return scale.coerceIn(1_000L, 1_000_000_000L)
    }

    private fun readTracks(reader: Reader, end: Int, notes: ArrayList<String>): Track? {
        var found: Track? = null
        var imageSubtitles = 0
        while (reader.pos < end) {
            val id = reader.readId() ?: break
            val size = reader.readSize()
            if (size < 0) break
            val entryEnd = limitOf(reader, size, end)
            if (id == TRACK_ENTRY) {
                var number = -1L
                var type = 0L
                var codec = ""
                var language: String? = null
                while (reader.pos < entryEnd) {
                    val fieldId = reader.readId() ?: break
                    val fieldSize = reader.readSize()
                    if (fieldSize < 0) break
                    val fieldEnd = limitOf(reader, fieldSize, entryEnd)
                    when (fieldId) {
                        TRACK_NUMBER -> if (fieldSize in 1..8) number = reader.readUnsigned(fieldSize.toInt())
                        TRACK_TYPE -> if (fieldSize in 1..8) type = reader.readUnsigned(fieldSize.toInt())
                        CODEC_ID -> codec = String(reader.take(fieldSize.toInt()), Charsets.US_ASCII)
                        LANGUAGE -> language = String(reader.take(fieldSize.toInt()), Charsets.UTF_8)
                    }
                    reader.pos = fieldEnd
                }
                if (type == 0x11L) {
                    if ((codec == "S_TEXT/UTF8" || codec == "S_TEXT/ASS" || codec == "S_SSA") && found == null) {
                        found = Track(number, type, codec, language)
                    } else if (codec.startsWith("S_") || codec.startsWith("S_TEXT") ||
                        codec.startsWith("S_HDMV") || codec.startsWith("S_VOBSUB")
                    ) {
                        imageSubtitles++
                    }
                }
            }
            reader.pos = entryEnd
        }
        if (imageSubtitles > 0) notes += "$imageSubtitles 条图片字幕轨提不出文字"
        return found
    }

    private fun readCluster(reader: Reader, end: Int, track: Track?, scale: Long, out: ArrayList<RawCue>) {
        if (track == null) {
            reader.pos = end
            return
        }
        var clusterTime = 0L
        while (reader.pos < end) {
            val id = reader.readId() ?: break
            val size = reader.readSize()
            if (size < 0) break
            val elementEnd = limitOf(reader, size, end)
            when (id) {
                CLUSTER_TIMECODE -> if (size in 1..8) clusterTime = reader.readUnsigned(size.toInt())
                SIMPLE_BLOCK -> readBlock(reader, elementEnd, track, clusterTime, scale, null, out)
                BLOCK_GROUP -> readBlockGroup(reader, elementEnd, track, clusterTime, scale, out)
            }
            reader.pos = elementEnd
        }
    }

    private fun readBlockGroup(reader: Reader, end: Int, track: Track, clusterTime: Long, scale: Long, out: ArrayList<RawCue>) {
        var duration: Long? = null
        var blockSize = -1
        var blockStart = -1
        while (reader.pos < end) {
            val id = reader.readId() ?: break
            val size = reader.readSize()
            if (size < 0) break
            val elementEnd = limitOf(reader, size, end)
            when (id) {
                BLOCK -> {
                    blockStart = reader.pos
                    blockSize = size.toInt()
                }
                BLOCK_DURATION -> if (size in 1..8) duration = reader.readUnsigned(size.toInt())
            }
            reader.pos = elementEnd
        }
        if (blockStart >= 0 && blockSize >= 0) {
            reader.pos = blockStart
            readBlock(reader, blockStart + blockSize, track, clusterTime, scale, duration, out)
        }
    }

    private fun readBlock(
        reader: Reader,
        end: Int,
        track: Track,
        clusterTime: Long,
        scale: Long,
        duration: Long?,
        out: ArrayList<RawCue>,
    ) {
        val trackNo = reader.readVint(marker = false) ?: return
        if (reader.pos + 3 > end) return
        val relative = ((reader.data[reader.pos].toInt() and 0xFF) shl 8 or
            (reader.data[reader.pos + 1].toInt() and 0xFF)).toShort().toInt()
        reader.pos += 2
        val flags = reader.data[reader.pos].toInt() and 0xFF
        reader.pos += 1
        val lacing = (flags shr 1) and 0x03
        if (trackNo != track.number || lacing != 0) return
        val payload = String(reader.data, reader.pos, end - reader.pos, Charsets.UTF_8).replace("\u0000", "")
        val text = if (track.codec == "S_TEXT/ASS" || track.codec == "S_SSA") {
            decodeAssPayload(payload)
        } else {
            payload.replace("\\N", "\n").replace("\\n", "\n").replace(Regex("<[^>]*>"), "").trim()
        }
        if (text.isBlank()) return
        val startMs = ((clusterTime + relative) * scale) / 1_000_000L
        val durationMs = duration?.let { it * scale / 1_000_000L }
        out += RawCue(max(0, startMs), text, durationMs)
    }

    private fun limitOf(reader: Reader, size: Long, end: Int): Int =
        (reader.pos + size).coerceAtMost(end.toLong()).toInt()

    private class Reader(val data: ByteArray) {
        var pos = 0

        fun hasMore(): Boolean = pos < data.size

        fun readId(): Long? = readVint(marker = true)

        fun readSize(): Long {
            val start = pos
            val value = readVint(marker = false) ?: return -1
            val length = pos - start
            val allOnes = (1L shl (7 * length)) - 1
            return if (value == allOnes) -1 else value
        }

        fun readVint(marker: Boolean): Long? {
            if (pos >= data.size) return null
            val first = data[pos].toInt() and 0xFF
            if (first == 0) return null
            var mask = 0x80
            var length = 1
            while (first and mask == 0) {
                mask = mask shr 1
                length++
                if (mask == 0) return null
            }
            var value = if (marker) first.toLong() else (first and (mask - 1)).toLong()
            if (pos + length > data.size) return null
            for (index in 1 until length) {
                value = (value shl 8) or (data[pos + index].toLong() and 0xFF)
            }
            pos += length
            return value
        }

        fun readUnsigned(length: Int): Long {
            var value = 0L
            repeat(length) { value = (value shl 8) or (data[pos++].toLong() and 0xFF) }
            return value
        }

        fun take(length: Int): ByteArray {
            val out = data.copyOfRange(pos, minOf(data.size, pos + length))
            pos += length
            return out
        }
    }
}
