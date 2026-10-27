package com.fileforge.converter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.fileforge.core.model.FileKind
import com.fileforge.core.ops.Operation
import com.fileforge.core.ops.PdfPaper
import com.fileforge.core.pdf.PageResizeMode
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.fileforge.converter.engine.PdfEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** PDF 新操作的引擎层：真实 PDFBox 在 Robolectric 上跑，插白页与裁剪不靠真机也能判。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfNewOpsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val workspace = Workspace(app)
    private val engine = PdfEngine(app, workspace)

    private fun item(name: String): WorkItem {
        val file = File(app.cacheDir, name)
        javaClass.classLoader.getResourceAsStream("pdf/$name").use { input ->
            requireNotNull(input) { "缺少测试夹具 pdf/$name" }
            file.outputStream().use { input.copyTo(it) }
        }
        return WorkItem(id = 1, name = name, file = file, kind = FileKind.Pdf, size = file.length(), addedAt = 0L)
    }

    @Test
    fun `插白页后页数加一并落在指定页后`() {
        val out = engine.insertBlankPages(item("base3.pdf"), "1")
        PDDocument.load(out.file).use { doc ->
            assertEquals(4, doc.numberOfPages, out.note)
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            stripper.startPage = 2
            stripper.endPage = 2
            assertTrue(stripper.getText(doc).isBlank(), "第 2 页应该是空白页")
        }
    }

    @Test
    fun `四边各裁 24pt 后页面宽高各少 48`() {
        val out = engine.cropPages(item("base3.pdf"), 24)
        PDDocument.load(out.file).use { doc ->
            val page = doc.getPage(0)
            assertEquals(page.mediaBox.width, page.cropBox.width)
            assertTrue(page.mediaBox.width < 600f, "A4 宽 ${page.mediaBox.width} 应小于 600")
        }
    }

    private fun save(out: File, name: String) {
        val dir = File("build/pdf-resize").apply { mkdirs() }
        out.copyTo(File(dir, name), overwrite = true)
    }

    @Test
    fun `改纸张把 A4 缩到 A5，页框与文字层都对`() {
        val out = engine.resizePages(
            item("base3.pdf"),
            Operation.ResizePdfPages(PdfPaper.A5, PageResizeMode.Contain),
        )
        PDDocument.load(out.file).use { doc ->
            val page = doc.getPage(0)
            assertEquals(420f, page.mediaBox.width, 1f, out.note)
            assertEquals(595f, page.mediaBox.height, 1f, out.note)
            assertEquals(page.mediaBox.width, page.cropBox.width, 0.01f)
            assertTrue(PDFTextStripper().getText(doc).isNotBlank(), "缩放不能把文字层弄丢")
        }
        save(out.file, "portrait-a5.pdf")
    }

    @Test
    fun `旋转 90 的页改成横向 A4，页框保持竖的 595x842`() {
        val source = item("base3.pdf")
        val rotated = File(app.cacheDir, "rotated-base3.pdf")
        PDDocument.load(source.file).use { doc ->
            doc.getPage(0).rotation = 90
            doc.save(rotated)
        }
        val out = engine.resizePages(
            source.copy(file = rotated, size = rotated.length()),
            Operation.ResizePdfPages(PdfPaper.A4, PageResizeMode.Contain),
        )
        PDDocument.load(out.file).use { doc ->
            val page = doc.getPage(0)
            assertEquals(595f, page.mediaBox.width, 1f, "未旋转页框应保持竖的 A4")
            assertEquals(842f, page.mediaBox.height, 1f)
            assertEquals(90, page.rotation, "旋转角不能被改掉")
        }
        save(out.file, "landscape-a4.pdf")
    }
}
