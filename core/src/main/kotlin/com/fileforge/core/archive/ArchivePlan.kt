package com.fileforge.core.archive

import com.fileforge.core.util.SizeInput

/** 解压前的一次性判断：哪些条目能落盘、哪些要跳过、整包该不该直接拒。 */
sealed interface UnpackPlan {
    /** 可以解。[keep] 是真正要落盘的条目，[skipped] 带着跳过原因。 */
    class Go(val keep: List<ZipEntry>, val skipped: List<Pair<ZipEntry, String>>) : UnpackPlan
    /** 整包不做，理由要能直接念给用户听。 */
    class Refused(val reason: String) : UnpackPlan
}

/**
 * 压缩包进出的决策。
 *
 * 这个文件里没有任何字节操作，全是"该不该做、叫什么名字"的判断 —— 而这些判断才是
 * 真正会咬人的地方：`../../` 开头的条目名、口令包、声称 40 GB 的炸弹包、
 * 以及解出来两个同名文件。所以每条判据都单独可测。
 */
object ArchivePlan {

    /** 单个条目解出来最大多大。超过就整包拒 —— 手机上一个条目解爆堆是直接把进程顶掉。 */
    const val MAX_ENTRY_BYTES = 64L * SizeInput.MEGA

    /** 整个包声明解开后最大多大。 */
    const val MAX_TOTAL_BYTES = 512L * SizeInput.MEGA

    /**
     * 出文件名：**把路径压平成名字**，不建目录。
     *
     * 两件事一并解决了：
     *  - `../` 之类的上跳没地方跳 —— 我们不拿条目名拼任何路径，所以这不是"过滤掉危险字符"，
     *    是结构上根本不存在穿越这条路
     *  - `x/a.txt` 与 `y/a.txt` 压平成 `x_a.txt` 与 `y_a.txt`，不会互相覆盖
     *
     * 代价是目录结构只能从名字看出来，界面上要写明白。
     */
    fun flatten(name: String): String {
        val segments = name.split('/', '\\').filter { it.isNotBlank() && it != "." && it != ".." }
        if (segments.isEmpty()) return "未命名"
        val joined = segments.joinToString("_") { sanitize(it) }
        // 太长的一律截尾，但保住扩展名 —— 没有扩展名的文件在手机上等于打不开
        val extension = joined.substringAfterLast('.', "")
        if (joined.length <= MAX_NAME) return joined
        val keep = MAX_NAME - (if (extension.isEmpty()) 0 else extension.length + 1)
        return joined.take(keep.coerceAtLeast(4)) + if (extension.isEmpty()) "" else ".$extension"
    }

    private const val MAX_NAME = 96

    /** 换掉 Windows 共享目录里不能出现的字符与控制字符，顺手抹掉开头的点（不造隐藏文件）。 */
    private fun sanitize(segment: String): String {
        val cleaned = buildString(segment.length) {
            segment.forEach { ch ->
                append(if (ch.code < 0x20 || ch in ":*?\"<>|") '_' else ch)
            }
        }
        return cleaned.trimStart('.')
    }

    /** 一条包里的目录项（名字以 `/` 结尾）没有内容，不该被当成文件解出来。 */
    fun plan(archive: ZipArchive): UnpackPlan {
        if (archive.entries.isEmpty()) return UnpackPlan.Refused("这个包里一条文件都没有")
        val files = archive.entries.filterNot { it.isDirectory }
        if (files.isEmpty()) return UnpackPlan.Refused("这个包里只有目录，没有可解出的文件")
        if (files.all { it.isEncrypted }) {
            return UnpackPlan.Refused("整个包都带口令，本应用不做口令解压")
        }
        val total = files.sumOf { it.size }
        if (total > MAX_TOTAL_BYTES) {
            return UnpackPlan.Refused(
                "这个包声明解开后有 ${SizeInput.format(total)}，超过 ${SizeInput.format(MAX_TOTAL_BYTES)} 的上限，不硬解",
            )
        }
        val keep = ArrayList<ZipEntry>(files.size)
        val skipped = ArrayList<Pair<ZipEntry, String>>()
        files.forEach { entry ->
            when {
                entry.isEncrypted -> skipped += entry to "带口令"
                ZipMethod.of(entry.method) == null ->
                    skipped += entry to "用了解不了的压缩方式（${ZipLabel.of(entry.method)}）"
                entry.isSymlink -> skipped += entry to "是个符号链接，解出来等于在别人目录里放文件"
                entry.size > MAX_ENTRY_BYTES -> skipped += entry to "单条 ${SizeInput.format(entry.size)} 超过上限"
                else -> keep += entry
            }
        }
        if (keep.isEmpty()) return UnpackPlan.Refused("这个包里的文件全都解不了：${skipped.first().second}")
        return UnpackPlan.Go(keep, skipped)
    }

    /** 跳过项的说明文字，进结果详情。 */
    fun skippedNote(skipped: List<Pair<ZipEntry, String>>): String = when {
        skipped.isEmpty() -> ""
        skipped.size == 1 -> "跳过 1 条：${skipped.first().first.name}（${skipped.first().second}）"
        else -> "跳过 ${skipped.size} 条（${skipped.map { it.second }.distinct().joinToString("、")}）"
    }

    /**
     * 解出来的文件在工作台叫什么：**压缩包名做前缀**，条目名压平跟在后面。
     *
     * 前缀是为了同一次选两个包时 `a.zip/读我.txt` 与 `b.zip/读我.txt` 不会互相盖掉；
     * 总长有上限，因为 MediaStore 那一层的名字长度是有限的，超了是写入失败而不是报错。
     */
    fun outputName(archiveName: String, entryName: String): String {
        val base = com.fileforge.core.naming.OutputNaming.sanitize(
            com.fileforge.core.naming.OutputNaming.stem(archiveName),
        ).take(24)
        val flat = flatten(entryName)
        val extension = com.fileforge.core.naming.OutputNaming.extension(flat, "bin")
        val stem = if (flat.endsWith(".$extension")) flat.removeSuffix(".$extension") else flat
        val joined = "${base}_$stem".take(MAX_OUTPUT_STEM).trimEnd('.', ' ')
        return "$joined.$extension"
    }

    private const val MAX_OUTPUT_STEM = 88

    /** 包里同名条目在打包时编号而不是互相覆盖。 */
    fun uniqueNames(names: List<String>): List<String> {
        val taken = HashSet<String>()
        return names.map { raw ->
            val name = raw.ifBlank { "未命名" }
            if (taken.add(name)) return@map name
            val stem = name.substringBeforeLast('.')
            val extension = if ('.' in name) "." + name.substringAfterLast('.') else ""
            var index = 2
            var candidate = "${stem}_$index$extension"
            while (!taken.add(candidate)) {
                index++
                candidate = "${stem}_$index$extension"
            }
            candidate
        }
    }
}
