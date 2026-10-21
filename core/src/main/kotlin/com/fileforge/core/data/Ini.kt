package com.fileforge.core.data

import com.fileforge.core.json.Json
import com.fileforge.core.json.JsonArray
import com.fileforge.core.json.JsonBoolean
import com.fileforge.core.json.JsonNull
import com.fileforge.core.json.JsonNumber
import com.fileforge.core.json.JsonObject
import com.fileforge.core.json.JsonString

class IniException(message: String) : Exception(message)

/**
 * INI 的一个保守实现：`[节]`、`键=值` / `键:值`、整行 `;` / `#` 注释。
 *
 * 值一律是**文字**（不猜数字与真假），引号也不剥 —— 与 Python `configparser` 的默认行为一致，
 * 这样"我们写的别人读得懂、别人写的我们读得一样"。全局键（第一个节之前）按 Windows INI 的惯例
 * 读进来、放在空键 `""` 里；`configparser` 要求先有节，这种写法它不认（写出去时仍按原顺序排在节前）。
 */
object Ini {

    fun parse(text: String): Json {
        val sections = LinkedHashMap<String, Json>()
        val globals = LinkedHashMap<String, Json>()
        var current: MutableMap<String, Json> = globals
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) return@forEachIndexed
            if (line.startsWith("[")) {
                if (!line.endsWith("]")) throw IniException("第 ${index + 1} 行：节名要以 ] 收尾")
                val name = line.substring(1, line.length - 1).trim()
                if (name.isEmpty()) throw IniException("第 ${index + 1} 行：节名不能为空")
                current = when (val existing = sections[name]) {
                    null -> LinkedHashMap<String, Json>().also { sections[name] = JsonObject(it) }
                    is JsonObject -> existing.members as? MutableMap<String, Json>
                        ?: throw IniException("第 ${index + 1} 行：节「$name」是只读的（内部错误）")
                    else -> throw IniException("第 ${index + 1} 行：节「$name」与一个键重名")
                }
                return@forEachIndexed
            }
            val split = indexOfSeparator(line)
            if (split < 0) throw IniException("第 ${index + 1} 行：这不是 键=值（也认 键: 值）")
            val key = line.substring(0, split).trim()
            if (key.isEmpty()) throw IniException("第 ${index + 1} 行：键名不能为空")
            val value = line.substring(split + 1).trim()
            // 重复键按常见 INI 的做法：后写的赢；要保留两份请改用 JSON/YAML
            current[key] = JsonString(value)
        }
        if (globals.isNotEmpty()) sections[""] = JsonObject(globals)
        return JsonObject(sections)
    }

    fun write(json: Json): String {
        val root = json as? JsonObject
            ?: throw IniException("INI 顶层必须是一张表；这份顶层是 ${kindOf(json)}")
        val out = StringBuilder()
        (root.members[""] as? JsonObject)?.members?.forEach { (key, value) ->
            out.append(keyOf(key)).append('=').append(scalar(value, key)).append('\n')
        }
        root.members.forEach { (name, value) ->
            if (name.isEmpty()) return@forEach
            if (value !is JsonObject) out.append(keyOf(name)).append('=').append(scalar(value, name)).append('\n')
        }
        root.members.forEach { (name, value) ->
            if (name.isEmpty() || value !is JsonObject) return@forEach
            if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
            if (name.contains(']')) throw IniException("节名「$name」里有 ]，INI 摆不下")
            out.append('[').append(name).append("]\n")
            value.members.forEach { (key, item) ->
                out.append(keyOf(key)).append('=').append(scalar(item, key)).append('\n')
            }
        }
        return out.toString()
    }

    private fun scalar(json: Json, key: String): String = when (json) {
        is JsonString -> {
            if (json.stringValue.orEmpty().contains('\n')) throw IniException("「$key」的值里有换行，INI 摆不下")
            json.stringValue.orEmpty()
        }
        is JsonNumber -> json.numberText ?: "0"
        is JsonBoolean -> if (json.boolValue == true) "true" else "false"
        is JsonNull -> throw IniException("INI 没有 null")
        is JsonArray -> throw IniException("「$key」是数组，INI 一个键只能有一个值")
        is JsonObject -> throw IniException("「$key」是表，塞进一节里会读成别的东西")
    }

    private fun keyOf(key: String): String {
        if (key.contains('=') || key.contains(':') || key.contains('\n')) {
            throw IniException("键名「$key」里有 = 或 :，INI 摆不下")
        }
        if (key.isBlank()) throw IniException("键名不能是空白")
        return key
    }

    private fun indexOfSeparator(line: String): Int {
        val eq = line.indexOf('=')
        val colon = line.indexOf(':')
        return when {
            eq < 0 -> colon
            colon < 0 -> eq
            else -> minOf(eq, colon)
        }
    }

    private fun kindOf(json: Json): String = when (json) {
        is JsonObject -> "表"
        is JsonArray -> "数组"
        is JsonString -> "字符串"
        is JsonNumber -> "数字"
        is JsonBoolean -> "布尔"
        is JsonNull -> "null"
    }
}
