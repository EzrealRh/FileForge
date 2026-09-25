package com.fileforge.core

import com.fileforge.core.model.FileKind
import com.fileforge.core.model.FileTypeSniffer
import com.fileforge.core.ops.OperationKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 类型识别只看魔数、不信扩展名，所以每条判据都得拿真字节当夹具。
 * 判错的代价不是报错，而是"能做的操作整个不对" —— 界面上完全看不出来，
 * 比如 .m4a 曾经被判成 Mp4，于是纯音频文件被按视频去挑操作。
 */
class FileKindSniffTest {

    private fun s(text: String) = text.map { it.code }

    /** 造一份 64 字节的文件头：把若干 (偏移, 字节) 写进去，其余留零。 */
    private fun h(vararg at: Pair<Int, List<Int>>): ByteArray = ByteArray(64).also { b ->
        at.forEach { (off, vs) -> vs.forEachIndexed { i, v -> b[off + i] = v.toByte() } }
    }

    @Test
    fun `原有类型一个都不能判坏`() {
        assertEquals(FileKind.Pdf, FileTypeSniffer.sniff(h(0 to s("%PDF-1.7"))))
        assertEquals(FileKind.Png, FileTypeSniffer.sniff(h(0 to listOf(0x89) + s("PNG"))))
        assertEquals(FileKind.Jpeg, FileTypeSniffer.sniff(h(0 to listOf(0xFF, 0xD8, 0xFF, 0xE0))))
        assertEquals(FileKind.Gif, FileTypeSniffer.sniff(h(0 to s("GIF89a"))))
        assertEquals(FileKind.Bmp, FileTypeSniffer.sniff(h(0 to s("BM"))))
        assertEquals(FileKind.WebP, FileTypeSniffer.sniff(h(0 to s("RIFF"), 8 to s("WEBP"))))
        assertEquals(FileKind.WebM, FileTypeSniffer.sniff(h(0 to listOf(0x1A, 0x45, 0xDF, 0xA3), 20 to s("webm"))))
        assertEquals(FileKind.Mkv, FileTypeSniffer.sniff(h(0 to listOf(0x1A, 0x45, 0xDF, 0xA3), 20 to s("matroska"))))
        assertEquals(FileKind.Mp4, FileTypeSniffer.sniff(h(0 to listOf(0, 0, 1, 0xBA), 4 to s("ftyp"), 8 to s("isom"))))
        assertEquals(FileKind.QuickTime, FileTypeSniffer.sniff(h(4 to s("ftyp"), 8 to s("qt  "))))
        assertEquals(FileKind.Heic, FileTypeSniffer.sniff(h(4 to s("ftyp"), 8 to s("mif1"))))
        assertEquals(FileKind.Avif, FileTypeSniffer.sniff(h(4 to s("ftyp"), 8 to s("avif"))))
        assertEquals(FileKind.Zip, FileTypeSniffer.sniff(h(0 to listOf(0x50, 0x4B, 3, 4))))
        assertEquals(FileKind.Unknown, FileTypeSniffer.sniff(h(0 to s("who knows"))))
    }

    @Test
    fun `JPEG 的 FF D8 不能被当成裸帧音频`() {
        // 裸帧同步是"高三位全 1"，0xD8 不满足；这条专防把 mp3 判据写松之后吃掉 JPEG
        val jpeg = h(0 to listOf(0xFF, 0xD8, 0xFF, 0xDB))
        assertEquals(FileKind.Jpeg, FileTypeSniffer.sniff(jpeg))
        assertFalse(jpeg[1].toInt().and(0xE0) == 0xE0)
    }

    @Test
    fun `带 ID3 标签的 mp3 要认成音频而不是未知`() {
        // 标签在帧前面，所以必须先看 ID3 再看同步字，否则第一帧永远落在标签后面
        assertEquals(FileKind.Mp3, FileTypeSniffer.sniff(h(0 to s("ID3"), 3 to listOf(4, 0))))
        assertEquals(FileKind.Mp3, FileTypeSniffer.sniff(h(0 to listOf(0xFF, 0xFB, 0x90))))
        assertEquals(FileKind.Mp3, FileTypeSniffer.sniff(h(0 to listOf(0xFF, 0xF3))))
    }

    @Test
    fun `ADTS 与 MP3 靠 layer 两位分开`() {
        // ADTS 的 layer 恒为 00，MP3 不是；两种保护位（0xF1/0xF9）都得是 AAC
        assertEquals(FileKind.Aac, FileTypeSniffer.sniff(h(0 to listOf(0xFF, 0xF1))))
        assertEquals(FileKind.Aac, FileTypeSniffer.sniff(h(0 to listOf(0xFF, 0xF9))))
        assertEquals(FileKind.Mp3, FileTypeSniffer.sniff(h(0 to listOf(0xFF, 0xFA))))
    }

    @Test
    fun `同是 RIFF 容器靠第二标记分开`() {
        assertEquals(FileKind.Wav, FileTypeSniffer.sniff(h(0 to s("RIFF"), 8 to s("WAVE"))))
        assertEquals(FileKind.WebP, FileTypeSniffer.sniff(h(0 to s("RIFF"), 8 to s("WEBP"))))
    }

    @Test
    fun `容器型音频按容器认不猜编码`() {
        assertEquals(FileKind.Flac, FileTypeSniffer.sniff(h(0 to s("fLaC"))))
        assertEquals(FileKind.Ogg, FileTypeSniffer.sniff(h(0 to s("OggS"))))
        // OGG 里是 vorbis 还是 opus，前 64 字节分不出来 —— 只到容器为止，别编一个 audio/vorbis 出来
        assertEquals("audio/ogg", FileKind.Ogg.mimeType)
    }

    @Test
    fun `M4A 不能再算成视频`() {
        assertEquals(FileKind.M4a, FileTypeSniffer.sniff(h(4 to s("ftyp"), 8 to s("M4A "))))
        assertEquals(FileKind.M4a, FileTypeSniffer.sniff(h(4 to s("ftyp"), 8 to s("m4a "))))
        assertTrue(FileKind.M4a.isAudio)
        assertFalse(FileKind.M4a.isVideo)
    }

    @Test
    fun `音频有 mime 有短名且不算图不算视频`() {
        val audio = listOf(FileKind.Mp3, FileKind.Aac, FileKind.M4a, FileKind.Flac, FileKind.Ogg, FileKind.Wav)
        audio.forEach {
            assertTrue(it.isAudio, "${it.name} 应当是音频")
            assertFalse(it.isImage || it.isVideo, "${it.name} 不该同时是图或视频")
            assertTrue(it.mimeType.startsWith("audio/"), "${it.name} 的 mime 要落在 audio/ 下")
            assertTrue(it.badge.isNotBlank() && it.badge != "文件", "${it.name} 要有自己的短名")
        }
        assertEquals("audio/mpeg", FileKind.Mp3.mimeType)
        assertEquals("audio/mp4", FileKind.M4a.mimeType)
        assertEquals("MP3", FileKind.Mp3.badge)
    }

    @Test
    fun `音频现在还没有可做的操作时不许凭空冒出来`() {
        // 音频类型进得来工作台，但操作目录没配音频操作之前，可用列表必须是空的：
        // 给一个"点了必崩"的操作比给一个"暂时不能做"更糟
        assertEquals(emptyList<Any>(), OperationKind.applicable(setOf(FileKind.Mp3)))
        assertTrue(OperationKind.applicable(setOf(FileKind.Pdf)).all { it.name.isNotEmpty() })
    }
}
