package com.fileforge.core

import com.fileforge.core.data.Csv
import com.fileforge.core.data.Delimiter
import com.fileforge.core.doc.Html
import com.fileforge.core.text.LineEnding
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 把 tables.html 抽出来的表落到 `build/html-tables/`，交给 `tools/verify_html_tables.py` 与
 * **pandas.read_html**（另一套完整的"网页表格 → 二维表"实现）对位置。
 *
 * 对的是**标记落在第几行第几列**，不是整格文字相等 —— 两家的格内排版本来就不一样
 * （`<br>` 我们换行、pandas 并成空格；列表我们带记号、pandas 直接连读）。
 * 位置才是这轮真正会错、错了又看不出来的东西。
 */
class HtmlTableFixtureTest {

    @Test
    fun `夹具里的表逐张落盘`() {
        val source = javaClass.classLoader.getResourceAsStream("html/tables.html").use { input ->
            requireNotNull(input) { "缺少夹具 html/tables.html" }
            String(input.readBytes(), Charsets.UTF_8)
        }
        val found = Html.toTables(source)
        val dir = File("build/html-tables").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val manifest = StringBuilder()
        var written = 0
        found.forEachIndexed { index, table ->
            if (table.rows.isEmpty()) return@forEachIndexed
            written++
            File(dir, "t$written.csv").writeText(
                Csv.render(table.rows, Delimiter.Comma, LineEnding.Lf),
                Charsets.UTF_8,
            )
            manifest.append(written).append('\t').append(table.name)
                .append('\t').append(table.rows.size).append('x').append(table.rows.maxOf { it.size })
                .append('\t').append(table.notes.joinToString("；")).append('\n')
        }
        File(dir, "tables.manifest").writeText(manifest.toString(), Charsets.UTF_8)
        val skipped = found.filter { it.rows.isEmpty() }.map { "${it.name}：${it.notes.joinToString("；")}" }
        File(dir, "tables.skipped").writeText(skipped.joinToString("\n") + "\n", Charsets.UTF_8)
        assertTrue(written >= 4, "夹具该抽出几张表：$written")
        assertTrue(skipped.any { "没有格子" in it }, "空表要落到一份说明里，别静悄悄少一张：$skipped")

        // 每个标记都得在产物里，一个都不许丢
        val markers = Regex("\\b([ctxy]\\d{1,2}[ab]?)").findAll(source).map { it.value }.toList().distinct()
        val all = dir.listFiles()!!.filter { it.name.endsWith(".csv") }.joinToString("\n") { it.readText() }
        val missing = markers.filter { it !in all }
        // x1 / x2 那张只有散 td 的表 pandas 不认，我们认 —— 它也在产物里
        assertTrue(missing.isEmpty(), "这些标记没进产物：$missing")
    }
}
