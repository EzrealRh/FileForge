package com.fileforge.core.naming

object OutputNaming {

    /** "报告.pdf" -> "报告" */
    fun stem(fileName: String): String {
        val slash = maxOf(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'))
        val name = if (slash >= 0) fileName.substring(slash + 1) else fileName
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** "报告.pdf" -> "pdf"，没有扩展名时返回 fallback。 */
    fun extension(fileName: String, fallback: String = "pdf"): String {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return fallback
        return fileName.substring(dot + 1).lowercase()
    }

    /** 分割件按顺序编号：part 从 1 开始，零填充到总份数宽度（至少 2 位）。baseName 带扩展名会先去掉。 */
    fun part(baseName: String, part: Int, totalParts: Int, extension: String): String {
        val width = maxOf(2, totalParts.toString().length)
        val index = part.toString().padStart(width, '0')
        return "${sanitize(stem(baseName))}_part$index.$extension"
    }

    /** 带操作标记的输出名：报告_页100-150.pdf、照片_压缩.jpg。 */
    fun tagged(baseName: String, tag: String, extension: String): String {
        val clean = sanitize(stem(baseName))
        return if (tag.isBlank()) "$clean.$extension" else "${clean}_$tag.$extension"
    }

    /** 目标重名时补 (2)、(3)…… */
    fun unique(requested: String, taken: Set<String>): String {
        if (requested !in taken) return requested
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val ext = if (dot > 0) requested.substring(dot) else ""
        var index = 2
        while ("$stem ($index)$ext" in taken) index++
        return "$stem ($index)$ext"
    }

    /** 去掉文件系统不允许的字符，中文保留。 */
    fun sanitize(name: String): String {
        val illegal = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*', '\n', '\r', '\t')
        val cleaned = buildString(name.length) {
            for (ch in name) if (ch !in illegal) append(ch)
        }.trim().trimEnd('.', ' ')
        return cleaned.ifBlank { "file" }
    }
}
