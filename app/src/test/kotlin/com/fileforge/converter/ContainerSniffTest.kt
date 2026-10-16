package com.fileforge.converter

import com.fileforge.core.archive.ZipReader
import com.fileforge.core.model.FileKind
import com.fileforge.core.model.FileTypeSniffer
import com.fileforge.core.office.OoxmlParts
import com.fileforge.converter.data.FileSlices
import com.fileforge.converter.data.sniffedKindOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 真机导入那条链子的整串复现：读头 512 字节 → 判层 → 是 zip 就把"读部件的手"递进去再判一次。
 *
 * 应用侧（`Workspace.sniff` / `containerKind`）就是这一串。桌面判据是从文件直接进判层，
 * 看不见这一段 —— ODF 在真机上被当普通压缩包、整个格式没了入口，桌面几十条判据一条都没红。
 */
class ContainerSniffTest {

    /** 走应用自己那一条：导入与装载用的就是 `sniffedKindOf`，判据不能再自己抄一遍。 */
    private fun appSniff(file: File): FileKind = sniffedKindOf(file)

    /** ODF 那一族的规定写法：第一个条目就叫 `mimetype`，可以不压缩（stored）。 */
    private fun container(entries: List<Pair<String, ByteArray>>, stored: Set<String> = emptySet()): File {
        val file = File.createTempFile("container", ".bin").apply { deleteOnExit() }
        java.io.FileOutputStream(file).use { stream ->
            ZipOutputStream(stream).use { out ->
                entries.forEach { (name, bytes) ->
                    val entry = ZipEntry(name)
                    if (name in stored) {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    out.putNextEntry(entry)
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        return file
    }

    private fun ods(withMimetypeStored: Boolean): File = container(
        listOf(
            "mimetype" to "application/vnd.oasis.opendocument.spreadsheet".toByteArray(),
            "META-INF/manifest.xml" to ("<?xml version=\"1.0\"?><manifest:manifest " +
                "xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\">" +
                "<manifest:file-entry manifest:full-path=\"/\" " +
                "manifest:media-type=\"application/vnd.oasis.opendocument.spreadsheet\"/>" +
                "</manifest:manifest>").toByteArray(),
            "content.xml" to "<office:document-content/>".toByteArray(),
            "styles.xml" to "<office:document-styles/>".toByteArray(),
        ),
        if (withMimetypeStored) setOf("mimetype") else emptySet(),
    )

    @Test
    fun `压缩包判层要递进读部件的手，不然 ODF 一律算回 zip`() {
        val file = ods(withMimetypeStored = true)
        assertEquals(FileKind.Zip, FileTypeSniffer.sniff(file.readBytes().copyOf(512)), "头 512 字节只看得出是个 zip")
        // 只给名字（以前应用侧就是这么调的）：认不出 ODF，只能算回压缩包
        val namesOnly = FileSlices(file).use { slices ->
            val archive = ZipReader.read(slices, file.length())
            OoxmlParts.kindOf(archive.entries.map { entry -> entry.name })
        }
        assertEquals(FileKind.Zip, namesOnly, "没给手就不该猜，但也要说清这条为什么会降级")
        assertEquals(FileKind.Ods, appSniff(file), "给了手就该认成 ODS")
    }

    @Test
    fun `mimetype 被压缩过也要认得`() {
        assertEquals(FileKind.Ods, appSniff(ods(withMimetypeStored = false)))
    }

    @Test
    fun `EPUB 与 docx 那两条老路没被带坏`() {
        val docx = container(
            listOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "_rels/.rels" to "<Relationships/>".toByteArray(),
                "word/document.xml" to "<w:document/>".toByteArray(),
            ),
        )
        assertEquals(FileKind.Docx, appSniff(docx))

        val epub = container(
            listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to ("<container><rootfiles>" +
                    "<rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>").toByteArray(),
                "OEBPS/content.opf" to "<package/>".toByteArray(),
            ),
            stored = setOf("mimetype"),
        )
        assertEquals(FileKind.Epub, appSniff(epub))
    }

    /** 仓库里那份真夹具（odfpy 写的）：判层要认得出它是 ODS，不是压缩包。 */
    @Test
    fun `真夹具 ledgerods 判成 ODS`() {
        val fixture = File("../core/src/test/resources/odsread/ledger.ods").canonicalFile
        assertTrue(fixture.isFile, "夹具不在expected位置：${fixture.path}")
        assertEquals(FileKind.Ods, appSniff(fixture))
    }
}
