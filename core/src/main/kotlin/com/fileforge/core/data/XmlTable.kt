package com.fileforge.core.data

import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonArray
import com.fileforge.core.json.JsonNull
import com.fileforge.core.json.JsonObject
import com.fileforge.core.json.JsonRender
import com.fileforge.core.json.JsonString

/** 挑出来的那张表：行（已摊平，直接交给 [TableBridge]）、它来自哪个元素、以及"还有什么没进表"。
 *
 * 名字与下面那个 object 分开写：Kotlin 里同包下类与对象不能同名。 */
class PickedTable(val rows: Json, val path: String, val notes: List<String>)

/**
 * 从 XML 那棵树里挑出**能当行**的那一处重复元素。
 *
 * [Xml] 的约定是"子元素一律成数组"（这样"有几个孩子"不会丢），所以"是不是数组"说明不了任何事 ——
 * 这里判的是"哪一处是作者真在列东西"：条数最多优先，其次字段多的优先，再往浅的优先。
 * 挑中之后把每条摊成一行：
 *  - 只有一值的嵌套元素摊成 `父.子` 这样的列名（`<author><name>` 不该变成一格 JSON）
 *  - 真嵌套（多值）压成一格 JSON 文本并说明 —— 一行只能摆一格
 *  - 元素自己的文字进 `文本` 列（树里那格叫 `#text`，摆在表头没人看得懂）
 *  - 属性保留 `@` 前缀：`<a id="1"><id>2</id></a>` 里那两个是不同的东西，撞成一列就是丢数据
 */
object XmlTable {

    /** 摊平时放"元素自己的文字"的列名。 */
    const val TEXT_COLUMN = "文本"

    /** 一处可当行的候选：来自哪个路径、多深、几条、字段几个、摊好的行、以及哪些列是被压平的。 */
    private class Candidate(
        val path: String, val depth: Int, val count: Int, val keys: Int,
        val rows: List<Json>, val packed: List<String>,
    ) {
        /** 打分：条数占大头，字段数次之，深的不优先。 */
        val score: Int get() = count * 100 + keys * 10 - depth
    }

    fun pick(root: Json): PickedTable? {
        val candidates = ArrayList<Candidate>()
        collect(root, "", 0, candidates)
        if (candidates.isEmpty()) return null
        val best = candidates.maxByOrNull { it.score } ?: return null
        val notes = ArrayList<String>()
        notes += "行取自 ${best.path} 那一处重复元素（${best.count} 条）"
        val others = candidates.filter { it !== best && it.count > 1 }.distinctBy { it.path }
        if (others.isNotEmpty()) {
            notes += "另有 ${others.size} 处也像表（${others.joinToString("、") { "${it.path} ${it.count} 条" }}），没进这张表"
        }
        if (best.packed.isNotEmpty()) {
            notes += "${best.packed.size} 列是真嵌套（一个条目里那处有多条），压成了一格文本：${best.packed.joinToString("、")}"
        }
        return PickedTable(JsonArray(best.rows), best.path, notes)
    }

    /** 挑不出行时的说法：直说没有重复元素，并把下一步指出去。 */
    fun reasonWhyNot(root: Json): String? =
        if (pick(root) == null) "这份 XML 里没有重复出现的元素可当行 —— 整份搬过去走「XML 转为 JSON」那条" else null

    private fun collect(value: Json, path: String, depth: Int, into: ArrayList<Candidate>) {
        when (value) {
            is JsonArray -> {
                val items = value.arrayValue
                val packed = ArrayList<String>()
                asRows(items, packed)?.let { rows ->
                    into += Candidate(
                        path.ifBlank { "根" }, depth, items.size,
                        rows.maxOf { it.members.size }, rows, packed.distinct(),
                    )
                }
                items.forEach { collect(it, path, depth + 1, into) }
            }
            is JsonObject -> value.members.forEach { (name, child) ->
                collect(child, if (path.isEmpty()) name else "$path.$name", depth, into)
            }
            else -> Unit
        }
    }

    /** 一组条目能不能当行：全对象（每行一条）或全标量（单列摆一列，空元素算空格子）。混着的不算。 */
    private fun asRows(items: List<Json>, packed: ArrayList<String>): List<JsonObject>? {
        if (items.isEmpty()) return null
        val objects = items.filterIsInstance<JsonObject>()
        if (objects.size == items.size) return objects.map { flatten(it, "", packed) }
        if (items.all { it !is JsonArray && it !is JsonObject }) {
            return items.map { JsonObject(linkedMapOf(TEXT_COLUMN to it)) }
        }
        return null
    }

    /** 一个条目摊平成一行：单值的嵌套往下钻，多值的压成一格文本，撞名的列加序号。 */
    private fun flatten(item: JsonObject, prefix: String, packed: ArrayList<String>): JsonObject {
        val out = LinkedHashMap<String, Json>()
        item.members.forEach { (raw, value) ->
            val key = if (raw == Xml.TEXT) prefix + TEXT_COLUMN else "$prefix$raw"
            when (value) {
                is JsonObject -> flatten(value, "$key.", packed).members.forEach { (nested, child) -> put(out, nested, child) }
                is JsonArray -> {
                    val only = value.arrayValue.singleOrNull()
                    when {
                        only is JsonObject -> flatten(only, "$key.", packed).members.forEach { (nested, child) -> put(out, nested, child) }
                        only != null && scalarish(only) -> put(out, key, only)
                        else -> {
                            if (value.arrayValue.isNotEmpty()) packed += key
                            put(out, key, JsonString(JsonRender.render(value)))
                        }
                    }
                }
                else -> put(out, key, value)
            }
        }
        return JsonObject(out)
    }

    /** `父.子` 这种拼出来的列名会和别人撞上（元素名里本来就有点），撞上就编号，不互相盖掉。 */
    private fun put(into: LinkedHashMap<String, Json>, key: String, value: Json) {
        if (!into.containsKey(key)) {
            into[key] = value
            return
        }
        var suffix = 2
        while (into.containsKey("$key#$suffix")) suffix++
        into["$key#$suffix"] = value
    }

    private fun scalarish(value: Json): Boolean = value !is JsonArray && value !is JsonObject && value !is JsonNull
}
