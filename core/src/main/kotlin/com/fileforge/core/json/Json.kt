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
    val numberValue: Double? get() = null
    val boolValue: Boolean? get() = null
    val arrayValue: List<Json> get() = emptyList()
    val members: Map<String, Json> get() = emptyMap()

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

class JsonNumber(private val value: Double) : Json {
    override val numberValue get() = value
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

    private fun readNumber(): Json {
        val start = at
        if (peek() == '-' || peek() == '+') at++
        while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
        val token = text.substring(start, at)
        return token.toDoubleOrNull()?.let { JsonNumber(it) }
            ?: throw JsonException("「$token」不是数字")
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
