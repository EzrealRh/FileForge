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
        // 纯 ASCII 现在是"文本"而不是"未知" —— 转编码、转字幕都靠这一格进来。
        // 注意夹具要用真实字节：h() 造的数组后面补的是零，那在判据里就是二进制
        assertEquals(FileKind.Text, FileTypeSniffer.sniff("who knows what this is".toByteArray()))
        assertEquals(FileKind.Text, FileTypeSniffer.sniff("1\n00:00:01,000 --> 00:00:02,000\n字幕\n".toByteArray()))
        assertEquals(FileKind.Unknown, FileTypeSniffer.sniff(h(0 to listOf(0x01, 0x00, 0x7F, 0x00, 0x11, 0x22))), "带 NUL 的才算二进制")
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
    fun `音频只拿到音频操作`() {
        // 这一条钉的是"别把不相干的操作摆在音频上"：图片转换、PDF、GIF 那些
        // 混进来就是点了必崩的按钮。两条打包（zip 与 tar.gz）是例外 —— 打包对任何类型都成立。
        listOf(FileKind.Mp3, FileKind.Aac, FileKind.M4a, FileKind.Flac, FileKind.Ogg, FileKind.Wav).forEach {
            assertEquals(
                listOf(OperationKind.ConvertAudio, OperationKind.PackZip, OperationKind.PackTar),
                OperationKind.applicable(setOf(it)),
                "$it 只该给音频转换和打包",
            )
        }
    }

    @Test
    fun `视频能提取音频而图片不能`() {
        val video = OperationKind.applicable(setOf(FileKind.Mp4))
        assertTrue(OperationKind.ExtractAudio in video, "MP4 该能提取音频")
        assertTrue(OperationKind.ConvertAudio !in video, "视频本身不是音频转换的输入")
        val image = OperationKind.applicable(setOf(FileKind.Jpeg))
        assertFalse(OperationKind.ExtractAudio in image)
        assertFalse(OperationKind.ConvertAudio in image)
    }

    @Test
    fun `真实的 ico 夹具要认成图标`() {
        // 用真夹具而不是手编前缀：嗅探规则最容易在"开头碰巧对"的别的文件上误判
        listOf("ico/dib32.ico", "ico/pngico.ico").forEach { name ->
            val bytes = javaClass.classLoader.getResourceAsStream(name).use { requireNotNull(it).readBytes() }
            assertEquals(FileKind.Ico, FileTypeSniffer.sniff(bytes.take(256).toByteArray()), "$name 该认成图标")
        }
        // 开头是 00 00 01 00 但目录说不通的，不该被硬认成图标
        val fake = ByteArray(64).also { it[2] = 1; it[4] = 9 }
        assertTrue(FileTypeSniffer.sniff(fake) != FileKind.Ico, "条目数 9 但后面全是 0，目录说不通")
    }

    @Test
    fun `图标只给图标那两个操作`() {
        val ops = OperationKind.applicable(setOf(FileKind.Ico))
        assertTrue(OperationKind.IcoToImages in ops, "ico 该能拆图：$ops")
        assertFalse(OperationKind.ConvertImage in ops, "普通格式转换只会出一张，不该摆在图标上：$ops")
        assertFalse(OperationKind.ImageToIco in ops, "已经是图标了不必再做成图标")
        val png = OperationKind.applicable(setOf(FileKind.Png))
        assertTrue(OperationKind.ImageToIco in png, "PNG 该能做成图标")
        assertFalse(OperationKind.IcoToImages in png, "PNG 不是图标")
    }

    @Test
    fun `音频加图片的混选只剩打包`() {
        // 选了一个 mp3 一个 jpg：没有任何共同的转换操作，界面不该摆一个只对一半文件有效、
        // 跑完静默跳过另一半的按钮。唯一例外是打包 —— 把不相干的两类装进一个包正是它的用途。
        assertEquals(listOf(OperationKind.PackZip, OperationKind.PackTar), OperationKind.applicable(setOf(FileKind.Mp3, FileKind.Jpeg)))
        assertTrue(OperationKind.applicable(setOf(FileKind.Pdf)).isNotEmpty())
    }

    @Test
    fun `Office 三种类型各拿自己的操作`() {
        val docx = OperationKind.applicable(setOf(FileKind.Docx))
        assertTrue(OperationKind.OfficeToText in docx, "docx 该能抽文字：$docx")
        assertTrue(OperationKind.TextToPdf in docx, "docx 抽完就能排版印成 PDF：$docx")
        assertTrue(OperationKind.XlsxToCsv !in docx, "docx 没有格子可转 CSV")
        assertTrue(OperationKind.ImageToIco !in docx, "文档不该拿到图片操作：$docx")

        val pptx = OperationKind.applicable(setOf(FileKind.Pptx))
        assertTrue(OperationKind.OfficeToText in pptx && OperationKind.XlsxToCsv !in pptx, "pptx: $pptx")

        val xlsx = OperationKind.applicable(setOf(FileKind.Xlsx))
        assertTrue(OperationKind.XlsxToCsv in xlsx, "xlsx 该能转 CSV：$xlsx")
        assertTrue(OperationKind.OfficeToText !in xlsx, "表格不是连着读的正文：$xlsx")
        // 写出来的那两条是给 csv / json 用的，别在 xlsx 上再摆一个"转成 xlsx"
        assertTrue(
            OperationKind.CsvToXlsx !in xlsx && OperationKind.JsonToXlsx !in xlsx,
            "xlsx 不该拿到「写成 Excel」这两条：$xlsx",
        )

        val text = OperationKind.applicable(setOf(FileKind.Text))
        assertTrue(OperationKind.CsvToXlsx in text && OperationKind.JsonToXlsx in text, "文本该能写成 xlsx：$text")
        // YAML 那一族只看"是不是文本"，是不是合法 YAML 交给引擎判（判不动会直说）
        assertTrue(
            setOf(OperationKind.YamlToJson, OperationKind.JsonToYaml, OperationKind.YamlToCsv,
                OperationKind.CsvToYaml).all { it in text },
            "文本该能跟 YAML 互转：$text",
        )
        assertTrue(
            OperationKind.YamlToJson !in OperationKind.applicable(setOf(FileKind.Docx)),
            "docx 没有 YAML 可解析",
        )

        // 纯文本没有"丢图"这一说，抽取操作不给它
        assertTrue(OperationKind.OfficeToText !in OperationKind.applicable(setOf(FileKind.Text)))
        // docx 和 txt 混着选：只剩两条路都走得通的操作
        assertEquals(
            setOf(OperationKind.TextToPdf, OperationKind.TextToDocx, OperationKind.PackZip, OperationKind.PackTar),
            OperationKind.applicable(setOf(FileKind.Docx, FileKind.Text)).toSet(),
        )
    }

    @Test
    fun `网页认标记，且与普通文本共用同一族操作`() {
        assertEquals(FileKind.Html, FileTypeSniffer.sniff("<!DOCTYPE html>\n<html>\n<head><title>甲</title>".toByteArray()))
        assertEquals(FileKind.Html, FileTypeSniffer.sniff("   \n\r<html lang=\"zh\"><body>甲</body></html>".toByteArray()))
        assertEquals(FileKind.Html, FileTypeSniffer.sniff("<div><p>只有正文片段的一张网页</p></div>".toByteArray()))
        // 带 BOM 也一样：浏览器存出来的网页十分有三字节的头
        assertEquals(
            FileKind.Html,
            FileTypeSniffer.sniff(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<html><body>甲</body></html>".toByteArray()),
        )

        // 这几份都不是网页：开头不是 '<'、是 XML 的另一族、或者只是写着尖括号的普通文字
        assertEquals(FileKind.Text, FileTypeSniffer.sniff("甲 <html> 只是文档里提了一句".toByteArray()))
        assertEquals(FileKind.Text, FileTypeSniffer.sniff("数学式子 a<b 且 c>d".toByteArray()))
        assertEquals(FileKind.Text, FileTypeSniffer.sniff("<?xml version=\"1.0\"?><svg></svg>".toByteArray()))
        assertEquals(FileKind.Text, FileTypeSniffer.sniff("# 一级标题\n- 项目".toByteArray()))

        val html = OperationKind.applicable(setOf(FileKind.Html))
        assertTrue(OperationKind.HtmlToText in html && OperationKind.HtmlToMarkdown in html, "网页该有这两条：$html")
        assertTrue(OperationKind.ConvertTextEncoding in html, "网页也要能换编码：$html")
        assertTrue(OperationKind.TextToPdf in html, "网页能印成 PDF：$html")
        assertTrue(OperationKind.TextToDocx in html, "网页能写成 Word：$html")
        // 表格没有"连着读的正文"，摆一条"写成 Word"只会产出一份把格子摊平的文档
        assertTrue(
            OperationKind.TextToDocx !in OperationKind.applicable(setOf(FileKind.Xlsx)),
            "xlsx 不该拿到「写成 Word」",
        )
        // 网页与纯文本混着选：这两条对两边都成立（引擎自己判有没有标签）
        assertTrue(
            setOf(OperationKind.HtmlToText, OperationKind.HtmlToMarkdown)
                .all { it in OperationKind.applicable(setOf(FileKind.Html, FileKind.Text)) },
        )
    }

    @Test
    fun `电子书给三条出路，不给普通 zip 的入口`() {
        val epub = OperationKind.applicable(setOf(FileKind.Epub))
        assertTrue(
            setOf(OperationKind.EpubToText, OperationKind.EpubToMarkdown, OperationKind.EpubToDocx)
                .all { it in epub }, "电子书该有这三条：$epub",
        )
        assertEquals("application/epub+zip", FileKind.Epub.mimeType)
        assertEquals("EPUB", FileKind.Epub.badge)
        // 技术上它是个 zip，但摆"解压"等于让用户去翻一堆 XHTML —— 三条出路里没有这一条
        assertFalse(OperationKind.UnpackZip in epub, "电子书不该拿到解压按钮：$epub")
        assertTrue(OperationKind.PackZip in epub, "打包对类型不设限")
        // 与纯文本混选：没有对两边都成立的出路，只留打包
        assertEquals(listOf(OperationKind.PackZip, OperationKind.PackTar), OperationKind.applicable(setOf(FileKind.Epub, FileKind.Text)))
    }

    @Test
    fun `tar 认头部那五个字节加校验和，gz 认魔数`() {
        val block = ByteArray(512)
        "甲.txt".toByteArray(Charsets.UTF_8).copyInto(block, 0)
        "0000644".toByteArray().copyInto(block, 100)
        "00000000005".toByteArray().copyInto(block, 124)
        "00000000000".toByteArray().copyInto(block, 136)
        block[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(block, 257)
        "00".toByteArray().copyInto(block, 263)
        val sum = (0 until 512).sumOf { index -> if (index in 148 until 156) ' '.code else block[index].toInt() and 0xFF }
        "%06o".format(sum).toByteArray().copyInto(block, 148)
        block[154] = 0
        block[155] = ' '.code.toByte()
        assertEquals(FileKind.Tar, FileTypeSniffer.sniff(block))
        assertEquals(FileKind.Gzip, FileTypeSniffer.sniff(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0, 0, 0, 0, 0, 0, 3, 0, 0)))
        // 只有 ustar 那五个字、校验和不对：不算 tar（随便一份文件里都可能出现这五个字节）
        block[148] = '9'.code.toByte()
        assertTrue(FileTypeSniffer.sniff(block) != FileKind.Tar, "校验和不对还认成 tar")
        // 只看 64 字节永远到不了第 257 位 —— 头部要读够 512 才行
        assertTrue(FileTypeSniffer.sniff(block.copyOf(64)) != FileKind.Tar)
    }

    @Test
    fun `归档族的操作各归各的容器`() {
        val tar = OperationKind.applicable(setOf(FileKind.Tar))
        val gz = OperationKind.applicable(setOf(FileKind.Gzip))
        assertTrue(OperationKind.Untar in tar && OperationKind.Untar in gz, "$tar / $gz")
        // zip 的按钮不给 tar：那边解不了 tar 的条目布局
        assertFalse(OperationKind.UnpackZip in tar, "tar 上不该有解压 ZIP")
        assertFalse(OperationKind.Untar in OperationKind.applicable(setOf(FileKind.Zip)), "zip 上不该有解开 tar")
        // 打包不设限：两种打包对任何类型都成立
        assertTrue(OperationKind.PackTar in OperationKind.applicable(setOf(FileKind.Pdf, FileKind.Jpeg)))
        // 文本与 tar 混选：只剩两条打包
        assertEquals(
            setOf(OperationKind.PackZip, OperationKind.PackTar),
            OperationKind.applicable(setOf(FileKind.Text, FileKind.Tar)).toSet(),
        )
        assertEquals("application/x-tar", FileKind.Tar.mimeType)
        assertEquals("TAR", FileKind.Tar.badge)
        assertEquals("GZ", FileKind.Gzip.badge)
    }
}
