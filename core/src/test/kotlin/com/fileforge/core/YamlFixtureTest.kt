package com.fileforge.core

import com.fileforge.core.data.Yaml
import com.fileforge.core.json.JsonRender
import java.io.File
import org.junit.jupiter.api.Test

/**
 * 把 YAML 夹具的产物落到 `build/yaml/`，交给 `tools/verify_yaml.py` 与 **PyYAML** 对：
 * 同一份文件两家读出来必须是一样的树，我们写出的 YAML 交给 PyYAML 读回来也得是同一棵树。
 *
 * 三份夹具各管一段：`config` 是形状（嵌套、锚点、块标量、行内写法），
 * `typing` 是两家判据**一致**的标量类型，`divergent` 是故意不一致的那一批
 * （1.1 与 1.2 的分歧），判据脚本逐条钉住"我们保持文字、PyYAML 变成别的"。
 */
class YamlFixtureTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("yaml/$name.yaml").use { input ->
            requireNotNull(input) { "缺少夹具 yaml/$name.yaml" }
            String(input.readBytes(), Charsets.UTF_8)
        }

    private val names = listOf("config", "typing", "divergent")

    @Test
    fun `三份夹具都落 JSON、我们写的 YAML 与转一圈回来的 JSON`() {
        val dir = File("build/yaml").apply { mkdirs() }
        names.forEach { name ->
            val tree = Yaml.parse(fixture(name))
            File(dir, "$name.json").writeText(JsonRender.render(tree) + "\n", Charsets.UTF_8)
            val written = Yaml.write(tree)
            File(dir, "$name.out.yaml").writeText(written, Charsets.UTF_8)
            // 自己写出去的自己再读回来。判"是不是同一棵树"交给判据脚本按**数值**比 ——
            // 数字的写法在两边可以不同（1.5e3 与 1500.0 是同一个数），字符串比会误报
            File(dir, "$name.round.json").writeText(
                JsonRender.render(Yaml.parse(written)) + "\n", Charsets.UTF_8,
            )
        }
    }
}
