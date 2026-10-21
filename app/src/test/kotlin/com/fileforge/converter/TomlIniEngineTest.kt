package com.fileforge.converter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.fileforge.core.model.FileKind
import com.fileforge.core.ops.Operation
import com.fileforge.converter.data.WorkItem
import com.fileforge.converter.data.Workspace
import com.fileforge.converter.engine.TextEngine
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** TOML / INI 接到产品这条链上：读文件、落产物、命名与说明都对（解析本身在 :core 已对过第三方裁判）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TomlIniEngineTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val workspace = Workspace(app)
    private val engine = TextEngine(workspace)
    private var nextId = 1L

    private fun item(text: String, name: String): WorkItem {
        val file = File(app.cacheDir, name).apply { parentFile?.mkdirs(); writeText(text) }
        return WorkItem(id = nextId++, name = name, file = file, kind = FileKind.Text, size = file.length(), addedAt = 0L)
    }

    @Test
    fun `TOML 转 JSON 落成 json 且内容对`() {
        val out = engine.tomlToJson(item("title = \"示例\"\n[owner]\nname = \"Tom\"\n", "conf.toml"), Operation.TomlToJson(2))
        assertTrue(out.name.endsWith(".json"), out.name)
        val text = out.file.readText()
        assertTrue(text.contains("\"title\": \"示例\""), text)
        assertTrue(text.contains("\"owner\""), text)
    }

    @Test
    fun `JSON 转 TOML 落成 toml 且对象变成表`() {
        val out = engine.jsonToToml(item("{\"title\":\"示例\",\"owner\":{\"name\":\"Tom\"}}", "conf.json"))
        assertTrue(out.name.endsWith(".toml"), out.name)
        val text = out.file.readText()
        assertTrue(text.contains("title = \"示例\""), text)
        assertTrue(text.contains("[owner]"), text)
    }

    @Test
    fun `INI 转 TOML 时全局键收进 global 表`() {
        val out = engine.iniToToml(item("a=1\n[s]\nb=2\n", "conf.ini"))
        val text = out.file.readText()
        assertTrue(text.contains("[global]"), text)
        assertTrue(text.contains("a = \"1\""), text)
        assertTrue(text.contains("[s]"), text)
    }

    @Test
    fun `TOML 的对象数组转成 CSV`() {
        val out = engine.tomlToCsv(
            item("[[rows]]\nname = \"A\"\n[[rows]]\nname = \"B\"\n", "rows.toml"),
            Operation.TomlToCsv(),
        )
        val text = out.file.readText()
        assertTrue(text.contains("name"), text)
        assertTrue(text.contains("A") && text.contains("B"), text)
    }

    @Test
    fun `YAML 转 INI 落成 ini`() {
        val out = engine.yamlToIni(item("name: Tom\nport: 8080\n", "conf.yaml"))
        val text = out.file.readText()
        assertTrue(text.contains("name=Tom"), text)
        assertFalse(text.contains("name: Tom"), "应该写成 INI 的等号，不是 YAML 的冒号")
    }
}
