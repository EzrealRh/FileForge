package com.fileforge.core.meta

/**
 * TIFF/IFD 读取 —— EXIF 的内部结构就是一个小 TIFF 文件。
 *
 * 只做"把身份信息显示出来"这一件事：大端小端都认（`II`/`MM` 头决定），
 * 子 IFD（Exif 与 GPS）按偏移跳过去，其余类型按字节数解释。
 * 读不出来的字段跳过而不是报错 —— 界面要的是"这台相机写了什么我们就显示什么"，
 * 一份结构怪异的相机文件不该让整次查看失败。
 */
object Tiff {

    /** type → 每个值的字节数。规范里这十个类型，其余按未知处理。 */
    private val typeSize = mapOf(
        1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8,
    )

    private const val EXIF_IFD = 0x8769
    private const val GPS_IFD = 0x8825
    private const val INTEROP_IFD = 0xA001

    /**
     * 读一份 EXIF（APP1 里 "Exif\0\0" 之后的那段）。返回给用户看的键值对，
     * GPS 合成一行方便直接显示，也方便判断"这照片带不带位置"。
     */
    fun read(tiff: ByteArray): List<Pair<String, String>> {
        if (tiff.size < 8) return emptyList()
        val head = String(tiff, 0, 2, Charsets.ISO_8859_1)
        val big = when (head) {
            "MM" -> true
            "II" -> false
            else -> return emptyList()
        }
        val out = ArrayList<Pair<String, String>>()
        val gps = ArrayList<Pair<String, String>>()
        val exif = ArrayList<Pair<String, String>>()
        if (!readIfd(tiff, u32(tiff, 4, big), big, out, gps, exif)) return out
        exif.forEach { (name, value) -> out += "拍摄参数 $name" to value }
        if (gps.isNotEmpty()) formatGps(gps)?.let { out += "GPS 位置" to it }
        return out
    }

    /** 读一个 IFD，把子 IFD 的内容分流到 gps / exif 两个桶里。 */
    private fun readIfd(
        tiff: ByteArray,
        at: Int,
        big: Boolean,
        into: ArrayList<Pair<String, String>>,
        gps: ArrayList<Pair<String, String>>,
        exif: ArrayList<Pair<String, String>>,
    ): Boolean {
        if (at < 0 || at + 2 > tiff.size) return false
        val count = u16(tiff, at, big)
        if (count > 255) return false                        // 正常 IFD 没这么多条，出现即当结构不可信
        for (slot in 0 until count) {
            val entry = at + 2 + slot * 12
            if (entry + 12 > tiff.size) return false
            val tag = u16(tiff, entry, big)
            val type = u16(tiff, entry + 2, big)
            val number = u32(tiff, entry + 4, big)
            val size = (typeSize[type] ?: return false).let { it * number }
            val where = if (size <= 4) entry + 8 else u32(tiff, entry + 8, big)
            if (where < 0 || where + size > tiff.size) continue
            when (tag) {
                GPS_IFD -> readIfd(tiff, u32(tiff, entry + 8, big), big, into = into, gps = gps, exif = exif)
                EXIF_IFD -> readIfd(tiff, u32(tiff, entry + 8, big), big, into = into, gps = gps, exif = exif)
                INTEROP_IFD -> Unit
                else -> {
                    val value = decode(tiff, where, type, number, big) ?: continue
                    val name = names[tag] ?: "标签 0x%04X".format(tag)
                    val shown = if (tag == ORIENTATION) orientationText(value) else value
                    when {
                        tag in gpsTags -> gps += name to shown
                        tag in exifTags -> exif += name to shown
                        else -> into += name to shown
                    }
                }
            }
        }
        return true
    }

    private fun decode(tiff: ByteArray, at: Int, type: Int, count: Int, big: Boolean): String? = when (type) {
        2 -> ascii(tiff, at, count)
        1 -> if (count == 1) "${tiff[at].toInt() and 0xFF}" else null
        3 -> if (count == 1) "${u16(tiff, at, big)}" else IntArray(count) { u16(tiff, at + it * 2, big) }.joinToString()
        4 -> if (count == 1) "${u32(tiff, at, big)}" else null
        9 -> if (count == 1) "${i32(tiff, at, big)}" else null
        5 -> rationals(tiff, at, count, big) { b, off -> u32(b, off, big).toLong() }
        10 -> rationals(tiff, at, count, big) { b, off -> i32(b, off, big) }
        else -> null
    }

    private fun rationals(tiff: ByteArray, at: Int, count: Int, big: Boolean, get: (ByteArray, Int) -> Long): String? {
        if (count > 8) return null
        return (0 until count).joinToString(" ") { slot ->
            val num = get(tiff, at + slot * 8)
            val den = get(tiff, at + slot * 8 + 4)
            if (den == 0L) "0" else "%.4g".format(num.toDouble() / den)
        }
    }

    private fun ascii(tiff: ByteArray, at: Int, count: Int): String? {
        val end = (at + count).coerceAtMost(tiff.size)
        val bytes = tiff.copyOfRange(at, end).takeWhile { it != 0.toByte() }.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1).trim()
        return text.takeIf { it.isNotEmpty() && it.all { c -> c.code in 32..126 || c in 'À'..'ÿ' } }
    }

    private const val ORIENTATION = 0x0112

    /** 方向标签规范里就八个取值，写成中文：光一个 6 用户读不出什么意思。 */
    private fun orientationText(raw: String): String = when (raw.trim().toIntOrNull()) {
        1 -> "1 正常"
        2 -> "2 左右镜像"
        3 -> "3 转一百八十度"
        4 -> "4 上下镜像"
        5 -> "5 镜像后转九十度"
        6 -> "6 顺时针九十度"
        7 -> "7 镜像后反转九十度"
        8 -> "8 逆时针九十度"
        else -> raw
    }

    /** 度分秒三个有理数 + 一个 N/S/E/W 引用 → 一行能读的位置。 */    private fun formatGps(gps: List<Pair<String, String>>): String? {
        val lat = gps.firstOrNull { it.first == "纬度" }?.second ?: return null
        val lon = gps.firstOrNull { it.first == "经度" }?.second ?: return null
        val latRef = gps.firstOrNull { it.first == "北纬/南纬" }?.second ?: "N"
        val lonRef = gps.firstOrNull { it.first == "东经/西经" }?.second ?: "E"
        return "$lat$latRef $lon$lonRef"
    }

    private val gpsTags = setOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val exifTags = setOf(
        0x829A, 0x920A, 0x9201, 0x9202, 0x8827, 0x9003, 0x9004, 0x9101, 0x920A, 0xA002, 0xA003, 0xA20E, 0xA20F,
    )

    private val names = mapOf(
        // IFD0
        0x010E to "图片说明", 0x010F to "制造商", 0x0110 to "机型", 0x0112 to "方向",
        0x011A to "水平分辨率", 0x011B to "分辨率单位", 0x0128 to "归属", 0x0131 to "作者",
        0x0132 to "修改时间", 0x013B to "系统", 0x8298 to "版权", 0x8769 to "Exif 子块",
        0x8825 to "GPS 子块", 0x8827 to "ISO",
        // Exif 子 IFD
        0x829A to "曝光时间", 0x920A to "光圈", 0x9201 to "焦距", 0x9202 to "胶片焦距",
        0x9003 to "拍摄时间", 0x9004 to "数字化时间", 0x9101 to "组件配置",
        0xA002 to "像素宽", 0xA003 to "像素高", 0xA20E to "闪光灯", 0xA20F to "测光方式",
        0x9286 to "BPS",
        // GPS 子 IFD
        1 to "北纬/南纬", 2 to "纬度", 3 to "东经/西经", 4 to "经度", 5 to "海拔参考",
        6 to "海拔", 7 to "时间", 8 to "卫星定位号",
    )

    private fun u16(b: ByteArray, at: Int, big: Boolean): Int {
        val hi = b[at].toInt() and 0xFF
        val lo = b[at + 1].toInt() and 0xFF
        return if (big) (hi shl 8) or lo else (lo shl 8) or hi
    }

    private fun u32(b: ByteArray, at: Int, big: Boolean): Int {
        val v = IntArray(4) { b[at + it].toInt() and 0xFF }
        return if (big) (v[0] shl 24) or (v[1] shl 16) or (v[2] shl 8) or v[3]
        else (v[3] shl 24) or (v[2] shl 16) or (v[1] shl 8) or v[0]
    }

    private fun i32(b: ByteArray, at: Int, big: Boolean): Long = (u32(b, at, big).toLong())
}
