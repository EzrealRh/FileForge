package com.fileforge.core.json

class JsonException(message: String) : Exception(message)

/**
 * 够读 GitHub API 那一小块就行的 JSON 树。
 *
 * :core 不引第三方库，是为了让"有没有新版"这条判断留在纯 JVM 里跑得动单测——
 * 真机验不了的东西已经够多了，能自己验的一律自己验。
 */
sealed interface Json {

    val stringValue: String? get() = null

    /**
     * 数字的**原文**。
     *
     * 格式化别人的 JSON 时不能把 `1` 印成 `1.0`、把 `1e20` 印成 `1.0E20`：
     * 看着是同一堆数， diff 却全是噪音，而大整数过一遍 Double 还会真的丢位。
     * 所以解析时把 token 原样留下，输出侧默认照抄。
     */
    val numberText: String? get() = null
    val numberValue: Double? get() = null
    val boolValue: Boolean? get() = null
    val arrayValue: List<Json> get() = emptyList()
    val members: Map<String, Json> get() = emptyMap()

    /** 是 null 字面量。和"这个字段没值"是两件事，所以分开判。 */
    val isNull: Boolean get() = this is JsonNull

    fun field(name: String): Json? = members[name]
    fun string(name: String): String? = field(name)?.stringValue
    fun long(name: String): Long? = field(name)?.numberValue?.toLong()
    fun flag(name: String): Boolean = field(name)?.boolValue == true
    fun list(name: String): List<Json> = field(name)?.arrayValue ?: emptyList()

    companion object {
        fun parse(text: String): Json = Reader(text).readDocument()
    }
}

class JsonString(private val value: String) : Json {
    override val stringValue get() = value
    override fun toString() = value
}

/** raw 是源文本里那个数字 token，value 是它的双精度读法。 */
class JsonNumber(val raw: String) : Json {
    override val numberText get() = raw
    override val numberValue get() = raw.toDoubleOrNull()
}

class JsonBoolean(private val value: Boolean) : Json {
    override val boolValue get() = value
}

object JsonNull : Json

class JsonArray(private val values: List<Json>) : Json {
    override val arrayValue get() = values
}

class JsonObject(private val values: Map<String, Json>) : Json {
    override val members get() = values
}

private class Reader(private val text: String) {
    private var at = 0

    fun readDocument(): Json {
        val value = readValue()
        skipSpace()
        if (at != text.length) throw JsonException("JSON 结尾还有多余内容")
        return value
    }

    private fun readValue(): Json {
        skipSpace()
        if (at >= text.length) throw JsonException("JSON 提前结束")
        return when (text[at]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonString(readString())
            't' -> readLiteral("true", JsonBoolean(true))
            'f' -> readLiteral("false", JsonBoolean(false))
            'n' -> readLiteral("null", JsonNull)
            else -> readNumber()
        }
    }

    private fun readObject(): Json {
        expect('{')
        val values = LinkedHashMap<String, Json>()
        skipSpace()
        if (peek() == '}') {
            at++
            return JsonObject(values)
        }
        while (true) {
            skipSpace()
            val key = readString()
            skipSpace()
            expect(':')
            values[key] = readValue()
            skipSpace()
            when (peek()) {
                ',' -> at++
                '}' -> {
                    at++
                    return JsonObject(values)
                }
                else -> throw JsonException("对象里该是逗号或右括号")
            }
        }
    }

    private fun readArray(): Json {
        expect('[')
        val values = ArrayList<Json>()
        skipSpace()
        if (peek() == ']') {
            at++
            return JsonArray(values)
        }
        while (true) {
            values += readValue()
            skipSpace()
            when (peek()) {
                ',' -> at++
                ']' -> {
                    at++
                    return JsonArray(values)
                }
                else -> throw JsonException("数组里该是逗号或右方括号")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            if (at >= text.length) throw JsonException("字符串没闭合")
            when (val char = text[at++]) {
                '"' -> return builder.toString()
                '\\' -> builder.append(readEscape())
                else -> builder.append(char)
            }
        }
    }

    private fun readEscape(): Char = when (val code = text.getOrNull(at++) ?: throw JsonException("转义写到一半")) {
        '"' -> '"'
        '\\' -> '\\'
        '/' -> '/'
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            if (at + 4 > text.length) throw JsonException("\\u 只有半截")
            val hex = text.substring(at, at + 4)
            at += 4
            hex.toIntOrNull(16)?.toChar() ?: throw JsonException("「$hex」不是合法的 \\u 码点")
        }
        else -> throw JsonException("不认识的反斜杠转义 \\$code")
    }

    /**
     * 数字按 **JSON 的语法**收，不是按 `String.toDouble` 能认什么收。
     *
     * 两者差得很远：后者认 `+5`、`1.`、`3d`、`Infinity`、`0x1p3`，而规范只认
     * `-?(0|[1-9]\d*)(\.\d+)?([eE][-+]?\d+)?`。放过去的话，解析+重新渲染会产出
     * 非法 JSON（`+5` 原样照抄出去），而渲染时是照抄原文的，所以必须在这一步就拦下。
     */
    private fun readNumber(): Json {
        val start = at
        if (peek() == '-') at++
        if (peek() == '0') {
            at++
        } else {
            if (at >= text.length || !text[at].isDigit()) throw JsonException("数字开头不对")
            while (at < text.length && text[at].isDigit()) at++
        }
        if (at < text.length && text[at] == '.') {
            at++
            if (at >= text.length || !text[at].isDigit()) throw JsonException("小数点后面没有数字")
            while (at < text.length && text[at].isDigit()) at++
        }
        if (at < text.length && text[at] in "eE") {
            val mark = at
            at++
            if (at < text.length && text[at] in "+-") at++
            if (at >= text.length || !text[at].isDigit()) {
                at = mark
            } else {
                while (at < text.length && text[at].isDigit()) at++
            }
        }
        val token = text.substring(start, at)
        return JsonNumber(token)
    }

    private fun readLiteral(literal: String, value: Json): Json {
        if (!text.startsWith(literal, at)) throw JsonException("读到的是坏字面量")
        at += literal.length
        return value
    }

    private fun skipSpace() {
        while (at < text.length && text[at].isWhitespace()) at++
    }

    private fun peek(): Char = text.getOrNull(at) ?: throw JsonException("JSON 提前结束")

    private fun expect(char: Char) {
        if (peek() != char) throw JsonException("该是「$char」，实际是「${peek()}」")
        at++
    }
}
