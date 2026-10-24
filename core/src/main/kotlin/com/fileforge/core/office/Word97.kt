package com.fileforge.core.office

import java.nio.charset.Charset

/**
 * Word 97-2003（.doc）二进制正文抽取。
 *
 * 正文不在固定偏移上：FIB 指向表流的 CLX，CLX 里是一张 piece table，每一片给出起点字符号
 * 与 WordDocument 里的字节位置；压缩片是单字节（CP1252），其余是 UTF-16LE。按固定偏移抽
 * 只能拿到第一段，后面的分节、表格与后加的内容都会丢。
 *
 * 图片、脚注正文、批注与修订痕迹不在正文流里，抽不出来；字段只留结果、不留指令。
 */
object Word97 {

    class Doc(val text: String, val notes: List<String>)

    private const val WORD_ID = 0xA5EC
    private const val FIB_TABLE_FLAG = 0x0200

    fun read(wordDocument: ByteArray, table0: ByteArray, table1: ByteArray): Doc {
        require(wordDocument.size >= 0x200) { "WordDocument 流过短，不像 Word 97 文档" }
        require(u16(wordDocument, 0) == WORD_ID) { "不是 Word 二进制文档（wIdent 不是 0xA5EC）" }
        val nFib = u16(wordDocument, 2)
        val flags = u16(wordDocument, 10)
        val notes = ArrayList<String>()
        notes += "Word 97-2003 二进制（nFib 0x${nFib.toString(16)}）"
        if (nFib < 101) {
            val start = i32(wordDocument, 0x18)
            val end = i32(wordDocument, 0x1C)
            require(start in 0 until end && end <= wordDocument.size) { "老 Word 的正文范围不合法" }
            val raw = String(wordDocument, start, end - start, Charset.forName("windows-1252"))
            notes += "Word 6/95 的正文按单字节读，中文可能乱码"
            return Doc(clean(raw, notes), notes)
        }
        val table = if (flags and FIB_TABLE_FLAG != 0) table1 else table0
        require(table.isNotEmpty()) { "缺少 Word 的表流（0Table / 1Table），文档可能损坏" }
        val fcClx = u32(wordDocument, 0x1A2)
        val lcbClx = u32(wordDocument, 0x1A6)
        require(lcbClx in 5..(64 * 1024 * 1024)) { "CLX 长度不合理：$lcbClx" }
        require(fcClx >= 0 && fcClx.toLong() + lcbClx <= table.size) { "CLX 指向表流之外" }

        val pieces = parsePieces(table, fcClx, lcbClx, notes)
        val builder = StringBuilder()
        for (piece in pieces) {
            val length = piece.chars
            if (length <= 0) continue
            val bytes = if (piece.compressed) length else length * 2
            require(piece.offset >= 0 && piece.offset.toLong() + bytes <= wordDocument.size) {
                "正文片越界（起点 ${piece.offset}，$bytes 字节）"
            }
            builder.append(
                if (piece.compressed) {
                    String(wordDocument, piece.offset, bytes, Charset.forName("windows-1252"))
                } else {
                    String(wordDocument, piece.offset, bytes, Charsets.UTF_16LE)
                },
            )
            if (builder.length > 64 * 1024 * 1024) {
                notes += "正文超过 64 MB，后面的部分没读"
                break
            }
        }
        require(builder.isNotEmpty()) { "piece table 里没有正文" }
        notes += "piece table ${pieces.size} 片"
        return Doc(clean(builder.toString(), notes), notes)
    }

    private class Piece(val offset: Int, val chars: Int, val compressed: Boolean)

    private fun parsePieces(table: ByteArray, fcClx: Int, lcbClx: Int, notes: ArrayList<String>): List<Piece> {
        val end = fcClx + lcbClx
        var pos = fcClx
        while (pos < end) {
            when (table[pos].toInt() and 0xFF) {
                0x01 -> {
                    if (pos + 3 > end) break
                    val size = u16(table, pos + 1)
                    pos += 3 + size
                }
                0x02 -> {
                    if (pos + 5 > end) break
                    val lcb = i32(table, pos + 1)
                    require(lcb >= 4 && pos + 5 + lcb <= end) { "piece table 长度不对：$lcb" }
                    val plc = pos + 5
                    val count = (lcb - 4) / 12
                    require(count in 1..1_000_000) { "piece 数不对：$count" }
                    val cps = IntArray(count + 1) { index -> i32(table, plc + index * 4) }
                    val pcdAt = plc + (count + 1) * 4
                    val out = ArrayList<Piece>(count)
                    for (index in 0 until count) {
                        val raw = i32(table, pcdAt + index * 8 + 2)
                        val compressed = raw and 0x40000000 != 0
                        var offset = raw and 0x3FFFFFFF
                        if (compressed) offset /= 2
                        val chars = cps[index + 1] - cps[index]
                        if (chars < 0) {
                            notes += "第 ${index + 1} 片字符数倒挂，跳过"
                            continue
                        }
                        out += Piece(offset, chars, compressed)
                    }
                    return out
                }
                else -> break
            }
        }
        error("表流里没有找到 piece table（CLX 开头不是 Prc / Pcdt）")
    }

    /** 字段只留结果；段落、换行、分页与表格单元格换成看得见的空白。 */
    private fun clean(raw: String, notes: ArrayList<String>): String {
        val out = StringBuilder(raw.length)
        var fieldDepth = 0
        var inInstruction = 0
        var pictures = 0
        for (ch in raw) {
            when (ch) {
                '\u0013' -> { fieldDepth++; inInstruction++ }
                '\u0014' -> if (inInstruction > 0) inInstruction--
                '\u0015' -> {
                    if (fieldDepth > 0) fieldDepth--
                    if (inInstruction > 0) inInstruction--
                }
                else -> {
                    if (inInstruction > 0) continue
                    when (ch) {
                        '\r', '\u000B', '\u000C' -> out.append('\n')
                        '\u0007' -> out.append('\t')
                        '\u0001' -> pictures++
                        '\u0002', '\u0000', '\u0008', '\u001E', '\u001F' -> Unit
                        else -> if (ch == '\t' || ch == '\n' || ch >= ' ') out.append(ch)
                    }
                }
            }
        }
        if (pictures > 0) notes += "$pictures 张图片不搬"
        val text = out.toString()
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("\\t{2,}"), "\t")
            .trim()
        require(text.isNotEmpty()) { "这份 Word 文档没有可抽的正文（可能全是图片）" }
        return text
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun i32(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun u32(data: ByteArray, offset: Int): Int = i32(data, offset).also {
        require(it >= 0) { "偏移超过 2 GB，不支持" }
    }
}
