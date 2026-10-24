package com.fileforge.core.office

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Excel 97-2003（BIFF8，BIFF5 的常见单元格也认）读成"每张表一张格子表"。
 *
 * 单元格的来源只有几种记录：共享字符串（SST）、内联字符串（LABEL）、数字（NUMBER/RK/MULRK）、
 * 公式的缓存结果（FORMULA + STRING）与布尔/错误。日期在文件里就是数字，靠 XF → FORMAT 的
 * 格式串判断；判不出来时保留数字并说明，不按"看起来像日期"硬猜。
 */
object Biff {

    class Sheet(val name: String, val rows: List<List<String>>, val notes: List<String>)

    class Result(val sheets: List<Sheet>, val notes: List<String>)

    private const val BOF = 0x0809
    private const val EOF = 0x000A
    private const val BOUNDSHEET = 0x0085
    private const val SST = 0x00FC
    private const val CONTINUE = 0x003C
    private const val FORMAT = 0x041E
    private const val XF = 0x00E0
    private const val DATEMODE = 0x0022
    private const val LABELSST = 0x00FD
    private const val LABEL = 0x0204
    private const val RSTRING = 0x00D6
    private const val NUMBER = 0x0203
    private const val RK = 0x027E
    private const val MULRK = 0x00BD
    private const val BOOLERR = 0x0205
    private const val FORMULA = 0x0006
    private const val STRING = 0x0207

    private class Rec(val id: Int, val payload: ByteArray)
    private class Bound(val name: String, val position: Int)

    fun read(workbook: ByteArray): Result {
        val records = records(workbook)
        require(records.isNotEmpty() && records[0].id == BOF) { "不是 Excel 97-2003 的工作簿流（开头不是 BOF）" }
        val version = u16(records[0].payload, 0)
        val biff5 = version == 0x0500
        val shared = ArrayList<String>()
        val formats = HashMap<Int, String>()
        val xfFormats = ArrayList<Int>()
        val bounds = ArrayList<Bound>()
        var date1904 = false
        val globalNotes = ArrayList<String>()

        var index = 0
        while (index < records.size) {
            val record = records[index]
            when (record.id) {
                EOF -> break
                BOUNDSHEET -> {
                    val position = i32(record.payload, 0)
                    bounds += Bound(sheetName(record.payload, 6), position)
                }
                SST -> {
                    val buffer = ByteArrayOutputStream()
                    buffer.write(record.payload)
                    var next = index + 1
                    while (next < records.size && records[next].id == CONTINUE) {
                        buffer.write(records[next].payload)
                        next++
                    }
                    if (next > index + 1) globalNotes += "共享字符串表跨了 ${next - index - 1} 条 CONTINUE，按拼接后的字节读"
                    runCatching { readSst(buffer.toByteArray(), shared) }
                        .onFailure { globalNotes += "共享字符串表解析失败：${it.message}" }
                    index = next - 1
                }
                FORMAT -> {
                    val id = u16(record.payload, 0)
                    formats[id] = readUnicode(record.payload, 2, biff5).first
                }
                XF -> if (record.payload.size >= 4) xfFormats += u16(record.payload, 2)
                DATEMODE -> date1904 = u16(record.payload, 0) == 1
            }
            index++
        }
        require(bounds.isNotEmpty()) { "工作簿里一张表都没有" }
        val sheets = bounds.map { bound ->
            val notes = ArrayList<String>()
            val rows = readSheet(workbook, records, bound, shared, formats, xfFormats, date1904, biff5, notes)
            Sheet(bound.name.ifBlank { "未命名" }, rows, notes)
        }
        return Result(sheets, globalNotes)
    }

    private fun readSheet(
        workbook: ByteArray,
        all: List<Rec>,
        bound: Bound,
        shared: List<String>,
        formats: Map<Int, String>,
        xfFormats: List<Int>,
        date1904: Boolean,
        biff5: Boolean,
        notes: ArrayList<String>,
    ): List<List<String>> {
        val cells = HashMap<Int, HashMap<Int, String>>()
        var maxRow = -1
        var maxCol = -1
        var pendingFormulaCell: Pair<Int, Int>? = null
        val records = recordsAt(workbook, all, bound.position)
        fun put(row: Int, col: Int, value: String) {
            if (row < 0 || col < 0 || col > 16_383) return
            cells.getOrPut(row) { HashMap() }[col] = value
            if (row > maxRow) maxRow = row
            if (col > maxCol) maxCol = col
        }
        for (record in records) {
            val data = record.payload
            when (record.id) {
                EOF -> break
                LABELSST -> {
                    if (data.size >= 10) {
                        val value = shared.getOrNull(i32(data, 6).coerceAtLeast(0)).orEmpty()
                        put(u16(data, 0), u16(data, 2), value)
                    }
                }
                LABEL -> if (data.size >= 6) {
                    put(u16(data, 0), u16(data, 2), readUnicode(data, 6, biff5).first)
                }
                RSTRING -> if (data.size >= 6) {
                    put(u16(data, 0), u16(data, 2), readUnicode(data, 6, biff5).first)
                }
                NUMBER -> if (data.size >= 14) {
                    val raw = data.copyOfRange(6, 14)
                    val value = Double.fromBits(leLong(raw, 0))
                    val row = u16(data, 0)
                    val col = u16(data, 2)
                    put(row, col, numberText(value, u16(data, 4), formats, xfFormats, date1904))
                }
                RK -> if (data.size >= 10) {
                    put(u16(data, 0), u16(data, 2), numberText(decodeRk(i32(data, 6)), u16(data, 4), formats, xfFormats, date1904))
                }
                MULRK -> {
                    if (data.size >= 6) {
                        val row = u16(data, 0)
                        val first = u16(data, 2)
                        val count = (data.size - 6) / 6
                        for (offset in 0 until count) {
                            val xf = u16(data, 4 + offset * 6)
                            val rk = i32(data, 6 + offset * 6)
                            put(row, first + offset, numberText(decodeRk(rk), xf, formats, xfFormats, date1904))
                        }
                    }
                }
                BOOLERR -> if (data.size >= 8) {
                    val error = data[7].toInt() != 0
                    val value = data[6].toInt() and 0xFF
                    put(u16(data, 0), u16(data, 2), if (error) "#ERR$value" else if (value == 0) "FALSE" else "TRUE")
                }
                FORMULA -> if (data.size >= 20) {
                    val row = u16(data, 0)
                    val col = u16(data, 2)
                    val xf = u16(data, 4)
                    if ((data[12].toInt() and 0xFF) == 0xFF && (data[13].toInt() and 0xFF) == 0xFF) {
                        when (data[6].toInt() and 0xFF) {
                            0 -> pendingFormulaCell = row to col
                            1 -> put(row, col, if (data[8].toInt() == 0) "FALSE" else "TRUE")
                            2 -> put(row, col, "#ERR")
                            3 -> put(row, col, "")
                        }
                    } else {
                        val value = Double.fromBits(leLong(data, 6))
                        put(row, col, numberText(value, xf, formats, xfFormats, date1904))
                    }
                }
                STRING -> {
                    val target = pendingFormulaCell
                    if (target != null) {
                        put(target.first, target.second, readUnicode(data, 0, biff5).first)
                        pendingFormulaCell = null
                    }
                }
            }
        }
        if (maxRow < 0 || maxCol < 0) {
            notes += "这张表是空的"
            return emptyList()
        }
        return (0..maxRow).map { row ->
            val line = cells[row] ?: HashMap()
            val values = (0..maxCol).map { col -> line[col].orEmpty() }.toMutableList()
            while (values.isNotEmpty() && values.last().isEmpty()) values.removeAt(values.size - 1)
            values
        }.dropLastWhile { it.isEmpty() }
    }

    private fun records(workbook: ByteArray): List<Rec> {
        val out = ArrayList<Rec>()
        var offset = 0
        while (offset + 4 <= workbook.size) {
            val id = u16(workbook, offset)
            val length = u16(workbook, offset + 2)
            if (offset + 4 + length > workbook.size) break
            out += Rec(id, workbook.copyOfRange(offset + 4, offset + 4 + length))
            offset += 4 + length
        }
        return out
    }

    /** 表的子流按 BOUNDSHEET 给的绝对偏移读起，读到它自己的 EOF 为止。 */
    private fun recordsAt(workbook: ByteArray, all: List<Rec>, position: Int): List<Rec> {
        if (position <= 0 || position >= workbook.size) return all
        val out = ArrayList<Rec>()
        var offset = position
        while (offset + 4 <= workbook.size) {
            val id = u16(workbook, offset)
            val length = u16(workbook, offset + 2)
            if (offset + 4 + length > workbook.size) break
            out += Rec(id, workbook.copyOfRange(offset + 4, offset + 4 + length))
            offset += 4 + length
            if (id == EOF) break
        }
        return out
    }

    private fun readSst(payload: ByteArray, into: ArrayList<String>) {
        if (payload.size < 8) return
        val unique = i32(payload, 4)
        var cursor = Cursor(payload, 8)
        var index = 0
        while (index < unique && cursor.pos < payload.size) {
            into += readUnicode(cursor, biff5 = false).first
            index++
        }
    }

    private class Cursor(val data: ByteArray, var pos: Int)

    /** BIFF8 的 Unicode 字符串：2 字节字符数 + 1 字节 flags；压缩串每字符 1 字节，否则 UTF-16LE。 */
    private fun readUnicode(data: ByteArray, offset: Int, biff5: Boolean): Pair<String, Int> {
        if (biff5) {
            if (offset >= data.size) return "" to data.size
            val length = data[offset].toInt() and 0xFF
            val end = minOf(data.size, offset + 1 + length)
            return String(data, offset + 1, end - offset - 1, Charsets.ISO_8859_1) to end
        }
        if (offset + 3 > data.size) return "" to data.size
        val count = u16(data, offset)
        val flags = data[offset + 2].toInt() and 0xFF
        var pos = offset + 3
        val rich = flags and 0x08 != 0
        val extended = flags and 0x04 != 0
        var runs = 0
        var extSize = 0
        if (rich && pos + 2 <= data.size) {
            runs = u16(data, pos)
            pos += 2
        }
        if (extended && pos + 4 <= data.size) {
            extSize = i32(data, pos)
            pos += 4
        }
        val utf16 = flags and 0x01 != 0
        val byteCount = count * if (utf16) 2 else 1
        val end = minOf(data.size, pos + byteCount)
        val text = if (utf16) String(data, pos, end - pos, Charsets.UTF_16LE)
        else String(data, pos, end - pos, Charsets.ISO_8859_1)
        val after = minOf(data.size, end + runs * 4 + extSize)
        return text to after
    }

    private fun readUnicode(cursor: Cursor, biff5: Boolean): Pair<String, Int> {
        val (text, next) = readUnicode(cursor.data, cursor.pos, biff5)
        cursor.pos = next
        return text to next
    }

    private fun sheetName(data: ByteArray, offset: Int): String {
        if (offset + 2 > data.size) return ""
        val length = data[offset].toInt() and 0xFF
        val flags = data[offset + 1].toInt() and 0xFF
        val pos = offset + 2
        val utf16 = flags and 0x01 != 0
        val end = minOf(data.size, pos + length * if (utf16) 2 else 1)
        return if (utf16) String(data, pos, end - pos, Charsets.UTF_16LE)
        else String(data, pos, end - pos, Charsets.ISO_8859_1)
    }

    private fun decodeRk(raw: Int): Double {
        val isInteger = raw and 0x02 != 0
        val divide = raw and 0x01 != 0
        var value = if (isInteger) {
            (raw shr 2).toDouble()
        } else {
            Double.fromBits((raw.toLong() and 0xFFFFFFFCL) shl 32)
        }
        if (divide) value /= 100.0
        return value
    }

    private fun numberText(
        value: Double,
        xf: Int,
        formats: Map<Int, String>,
        xfFormats: List<Int>,
        date1904: Boolean,
    ): String {
        val formatId = xfFormats.getOrNull(xf)
        val format = formatId?.let { formats[it] } ?: builtInFormat(formatId)
        if (format != null && isDateFormat(format)) return dateText(value, format, date1904)
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (abs(value) < 1e15 && value == value.roundToLong().toDouble()) return value.roundToLong().toString()
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    private fun builtInFormat(id: Int?): String? = when (id) {
        null -> null
        14 -> "m/d/yyyy"
        15 -> "d-mmm-yy"
        16 -> "d-mmm"
        17 -> "mmm-yy"
        18 -> "h:mm AM/PM"
        19 -> "h:mm:ss AM/PM"
        20 -> "h:mm"
        21 -> "h:mm:ss"
        22 -> "m/d/yyyy h:mm"
        45 -> "mm:ss"
        46 -> "[h]:mm:ss"
        47 -> "mm:ss.0"
        else -> null
    }

    private fun isDateFormat(format: String): Boolean {
        val cleaned = format.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^]]*]"), "").lowercase()
        return cleaned.any { it in "ymdhs" } && cleaned.none { it == '0' }
    }

    private fun dateText(serial: Double, format: String, date1904: Boolean): String {
        val timeOnly = !format.lowercase().any { it in "ymd" }
        val secondsTotal = (serial * 86_400.0).roundToLong()
        val base = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0)
        else if (serial >= 60) LocalDateTime.of(1899, 12, 30, 0, 0)
        else LocalDateTime.of(1899, 12, 31, 0, 0)
        val stamp = base.plusSeconds(secondsTotal)
        return if (timeOnly) stamp.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        else stamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).removeSuffix(" 00:00:00")
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

    private fun leLong(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = value or ((data[offset + index].toLong() and 0xFF) shl (8 * index))
        return value
    }
}
