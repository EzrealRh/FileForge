package com.fileforge.core.office

/**
 * PowerPoint 97-2003（.ppt）抽文字。
 *
 * PPT 的正文在 `PowerPoint Document` 流的记录树里：Document / Slide 容器下面挂
 * TextCharsAtom（UTF-16LE）或 TextBytesAtom（单字节）。这里按记录头逐层走，
 * 只收 Slide 与 Document 下的正文，母版与备注不抽；同一段文字在多次保存的修订里
 * 会出现好几遍，按"最后一次出现"去重。
 *
 * 图片、表格线、SmartArt 与备注正文不在这些记录里，抽不出来。
 */
object PptText {

    class Doc(val text: String, val notes: List<String>)

    private const val DOCUMENT = 1000
    private const val SLIDE = 1006
    private const val NOTES = 1008
    private const val MASTER = 1016
    private const val SLIDE_LIST = 4080
    private const val DRAWING = 1036
    private const val DRAWING_GROUP = 1035
    private const val TEXT_CHARS = 4000
    private const val TEXT_BYTES = 4008

    private val CONTAINERS = setOf(DOCUMENT, SLIDE, NOTES, MASTER, SLIDE_LIST, DRAWING, DRAWING_GROUP)

    fun read(stream: ByteArray): Doc {
        val found = ArrayList<String>()
        var atoms = 0
        walk(stream, 0, stream.size, 0, false, found) { atoms++ }
        require(found.isNotEmpty()) { "PPT 流里没有找到正文记录（可能这份演示只有图片）" }
        // 从最后一次保存往前收：同一段文字只留最新修订里的那一份
        val seen = HashSet<String>()
        val deduped = ArrayList<String>()
        for (index in found.indices.reversed()) {
            val text = found[index]
            if (seen.add(text)) deduped += text
        }
        deduped.reverse()
        val text = deduped.joinToString("\n\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        require(text.isNotEmpty()) { "PPT 的正文记录都是空的" }
        val notes = ArrayList<String>()
        notes += "PowerPoint 97-2003"
        notes += "$atoms 段正文记录，去重后 ${deduped.size} 段"
        notes += "母版与备注文字不抽，图片与表格线不搬"
        return Doc(text, notes)
    }

    private fun walk(
        data: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        inSlide: Boolean,
        out: ArrayList<String>,
        onAtom: () -> Unit,
    ) {
        if (depth > 32) return
        var pos = start
        while (pos + 8 <= end) {
            val type = u16(data, pos + 2)
            val length = i32(data, pos + 4)
            if (length < 0 || pos + 8L + length > end) break
            val body = pos + 8
            val bodyEnd = body + length
            when {
                type == TEXT_CHARS -> {
                    val text = clean(String(data, body, length, Charsets.UTF_16LE))
                    if (text.isNotBlank() && inSlide) {
                        out += text
                        onAtom()
                    }
                }
                type == TEXT_BYTES -> {
                    val text = clean(String(data, body, length, Charsets.ISO_8859_1))
                    if (text.isNotBlank() && inSlide) {
                        out += text
                        onAtom()
                    }
                }
                type in CONTAINERS -> {
                    val nextSlide = inSlide || type == SLIDE || type == DOCUMENT || type == SLIDE_LIST
                    walk(data, body, bodyEnd, depth + 1, nextSlide, out, onAtom)
                }
            }
            pos = bodyEnd
        }
    }

    private fun clean(raw: String): String = raw
        .replace('\r', '\n')
        .replace('\u000B', '\n')
        .filter { it == '\n' || it == '\t' || it >= ' ' }
        .lines()
        .joinToString("\n") { it.trim() }
        .trim()

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun i32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
