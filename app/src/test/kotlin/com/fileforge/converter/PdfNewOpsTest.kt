package com.fileforge.converter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.fileforge.core.model.FileKind
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.fileforge.converter.engine.PdfEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
}
