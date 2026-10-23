package com.fileforge.converter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.fileforge.core.model.FileKind
import com.fileforge.core.ops.Operation
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.fileforge.converter.engine.ArchiveEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 归档新库这一层：加密 ZIP 往返，以及 7z / RAR 真夹具的解码。库是纯 JVM，Robolectric 足够。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveEngineTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val workspace = Workspace(app)
    private val engine = ArchiveEngine()
    private var nextId = 1L

    private fun textItem(file: File, kind: FileKind = FileKind.Text) =
        WorkItem(id = nextId++, name = file.name, file = file, kind = kind, size = file.length(), addedAt = 0L)

    private fun fixture(name: String, kind: FileKind): WorkItem {
        val target = File(app.cacheDir, name)
        requireNotNull(javaClass.classLoader).getResourceAsStream("archives/$name").use { input ->
            requireNotNull(input) { "缺少夹具 archives/$name" }
            target.outputStream().use { input.copyTo(it) }
        }
        return textItem(target, kind)
    }

    @Test
    fun `加密 ZIP 打包再解开，内容一字不差；密码不对就失败`() {
        val dir = File(app.cacheDir, "arch-${System.nanoTime()}").apply { mkdirs() }
        val first = File(dir, "a.txt").apply { writeText("hello 文件工坊") }
        val second = File(dir, "b.txt").apply { writeText("second payload") }
        val packed = engine.pack(
            listOf(textItem(first), textItem(second)),
            Operation.PackArchive("1234"),
            workspace::newStagingFile,
        )
        assertTrue(packed.file.length() > 0)
        val zip = textItem(packed.file, FileKind.Zip)
        val unpacked = engine.unpack(zip, Operation.UnpackArchive("1234"), workspace::newStagingFile)
        assertEquals(2, unpacked.size, unpacked.joinToString { it.name })
        assertEquals(setOf("hello 文件工坊", "second payload"), unpacked.map { it.file.readText() }.toSet())

        assertThrows(Exception::class.java) {
            engine.unpack(zip, Operation.UnpackArchive("wrong-pass"), workspace::newStagingFile)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.unpack(zip, Operation.UnpackArchive(""), workspace::newStagingFile)
        }
    }

    @Test
    fun `真实 7z 夹具解得开`() {
        val outs = engine.unpackSevenZ(fixture("hello.7z", FileKind.SevenZ), Operation.UnpackSevenZ(), workspace::newStagingFile)
        assertTrue(outs.isNotEmpty(), "7z 里一个文件都没解出来")
        assertTrue(outs.all { it.file.length() > 0 }, outs.joinToString { "${it.name}:${it.file.length()}" })
    }

    @Test
    fun `真实 RAR4 与 RAR5 夹具解得开`() {
        val outs = engine.unpackRar(fixture("rar4.rar", FileKind.Rar), Operation.UnpackRar(), workspace::newStagingFile)
        assertTrue(outs.isNotEmpty(), "rar4.rar 里一个文件都没解出来")
        assertTrue(outs.all { it.file.length() > 0 }, outs.joinToString { "${it.name}:${it.file.length()}" })
    }

    @Test
    fun `RAR5 明确拒绝而不是装死`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            engine.unpackRar(fixture("rar5.rar", FileKind.Rar), Operation.UnpackRar(), workspace::newStagingFile)
        }
        assertTrue(error.message.orEmpty().contains("RAR5"), error.message)
    }
}
