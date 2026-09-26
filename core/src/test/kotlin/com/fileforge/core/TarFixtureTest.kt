package com.fileforge.core

import com.fileforge.core.archive.ArchivePlan
import com.fileforge.core.archive.Gzip
import com.fileforge.core.archive.Tar
import com.fileforge.core.archive.TarItem
import com.fileforge.core.archive.TarType
import com.fileforge.core.archive.UnpackPlan
import com.fileforge.core.archive.ZipReader
import com.fileforge.core.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 拿 **Python 的 tarfile 打出来的包**走一遍整条路，并把我们的产物落盘交给
 * `tools/verify_tar.py` 用 tarfile 与 gzip 反向复核（我们写的包别人读不读得动）。
 *
 * 期望值不写在这里：读 `tools/make_tar_fixtures.py` 生成的 manifest.json，
 * 让"造夹具的"、"判断的"与"判据"三处只有一个人说得上话。
 */
class TarFixtureTest {

    private fun resource(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("tar/$name").use { input ->
            requireNotNull(input) { "缺少夹具 tar/$name，先跑 python tools/make_tar_fixtures.py" }
            input.readBytes()
        }

    private fun manifest(): Json =
        Json.parse(String(resource("manifest.json"), Charsets.UTF_8))

    private fun kindOf(code: Char, name: String): String = when {
        code == TarType.Directory.code || name.endsWith("/") -> "dir"
        code == TarType.Symlink.code -> "symlink"
        code == TarType.Link.code -> "link"
        code == TarType.CharDevice.code -> "char"
        code == TarType.BlockDevice.code -> "block"
        code == TarType.Fifo.code -> "fifo"
        code == TarType.GnuLongName.code -> "longname"
        code == TarType.PaxHeader.code -> "pax"
        code == TarType.GnuSparse.code -> "sparse"
        else -> "file"
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun rows(name: String): List<String> {
        val bytes = bytesOf(name)
        val slicing = ZipReader.slicing(bytes)
        return Tar.read(bytes).entries.map { entry ->
            val body = if (entry.isDirectory) ByteArray(0) else Tar.dataOf(entry, slicing)
            listOf(
                // 目录条目名末尾那个斜杠两边都去掉：Python 的 tarfile 会抹掉它，
                // 我们内部留着它判"这是目录"，比对时不该为这一个字符各说一套
                name, entry.name.removeSuffix("/"), entry.size.toString(), entry.mtime.toString(),
                kindOf(entry.typeCode, entry.name), sha(body),
            ).joinToString("\t")
        }
    }

    /** `.gz` 结尾的先脱一层（顺带用真包验我们的 ungzip），其余照原样。 */
    private fun bytesOf(name: String): ByteArray {
        val raw = resource(name)
        if (!Gzip.isGzip(raw)) return raw
        val sink = ByteArrayOutputStream()
        Gzip.ungzip(ByteArrayInputStream(raw), sink)
        return sink.toByteArray()
    }

    @Test
    fun `每份 Python 打的包我们读出来一字不差`() {
        val declared = manifest().members.getValue("archives").arrayValue
        declared.forEach { archive ->
            val file = archive.members.getValue("file").stringValue
            val want = archive.members.getValue("members").arrayValue.map { row ->
                listOf(
                    file!!, row.members.getValue("name").stringValue, row.members.getValue("size").numberText,
                    row.members.getValue("mtime").numberText, row.members.getValue("kind").stringValue,
                    row.members.getValue("sha").stringValue,
                ).joinToString("\t")
            }
            assertEquals(want, rows(file!!), file)
        }
    }

    @Test
    fun `头块坏了就停在那里并把话说清楚`() {
        val broken = Tar.read(resource("broken.tar"))
        val good = manifest().members.getValue("broken").members.getValue("good_before").numberValue
        assertEquals(good?.toLong(), broken.entries.size.toLong(), "坏了以后还硬读出几条")
        assertTrue(broken.notes.any { it.contains("校验和") }, broken.notes.toString())
    }

    @Test
    fun `解出来的只有普通文件，链接与设备一律跳过`() {
        manifest().members.getValue("archives").arrayValue.forEach { archive ->
            val file = archive.members.getValue("file").stringValue!!
            val plan = ArchivePlan.plan(Tar.read(bytesOf(file))) as UnpackPlan.Go
            val want = archive.members.getValue("members").arrayValue
                .count { it.members.getValue("kind").stringValue == "file" }
            assertEquals(want, plan.keep.size, file)
            assertEquals(2, plan.skipped.size, "$file 该跳过符号链接与字符设备各一条")
            assertTrue(plan.skipped.map { it.second }.all { it.isNotBlank() }, plan.skipped.toString())
        }
    }

    @Test
    fun `产物落到 build 交给 tarfile 与 gzip 反向复核`() {
        val dir = File("build/tar").apply { mkdirs() }
        val all = manifest().members.getValue("archives").arrayValue
            .flatMap { archive -> rows(archive.members.getValue("file").stringValue!!) }
        File(dir, "listing.txt").writeText(all.joinToString("\n") + "\n", Charsets.UTF_8)

        // 我们写的包：成员名与内容都从 plain.tar 原样搬，判据只问"别人读出来是不是同一份"
        val source = resource("pax.tar")        // 挑这份：里面的中文长名才走得到 PAX 扩展头那条路
        val read = Tar.read(source)
        val slicing = ZipReader.slicing(source)
        val items = read.entries.filter { it.skipReason == null }.map { entry ->
            TarItem(entry.name, entry.size, entry.mtime) { ByteArrayInputStream(Tar.dataOf(entry, slicing)) }
        }
        val tar = Tar.write(items)
        File(dir, "written.tar").writeBytes(tar)
        File(dir, "written-from.txt").writeText("pax.tar", Charsets.UTF_8)
        val gz = ByteArrayOutputStream().also { sink ->
            Gzip.gzip(ByteArrayInputStream(tar), sink, mtime = 1_700_000_000L, name = "written.tar")
        }.toByteArray()
        File(dir, "written.tar.gz").writeBytes(gz)
        File(dir, "relisted.txt").writeText(
            Tar.read(tar).entries.joinToString("\n") { "${it.name}\t${it.size}\t${it.mtime}" } + "\n",
            Charsets.UTF_8,
        )
        assertEquals(items.map { "${it.name}\t${it.size}\t${it.mtime}" }, Tar.read(tar).entries.map { "${it.name}\t${it.size}\t${it.mtime}" })

        // 单个 .gz：内容交回 Python 核对，名字交回它按 RFC 1952 的字段位置核对
        val single = resource("single.gz")
        val gunzipped = ByteArrayOutputStream()
        Gzip.ungzip(ByteArrayInputStream(single), gunzipped)
        File(dir, "gunzip.bin").writeBytes(gunzipped.toByteArray())
        val declared = manifest().members.getValue("gz")
        assertEquals(declared.members.getValue("sha").stringValue, sha(gunzipped.toByteArray()))
        File(dir, "gunzip-name.txt").writeText(Gzip.readHeader(single)?.name ?: "", Charsets.UTF_8)
        File(dir, "notes.txt").writeText(
            (read.notes + Tar.read(resource("broken.tar")).notes).joinToString("\n") + "\n", Charsets.UTF_8,
        )
        // 坏包读到哪算完，也要落一份给 tarfile 比（两边停在同一处才算对）
        File(dir, "broken.txt").writeText(
            Tar.read(resource("broken.tar")).entries.joinToString("\n") { entry ->
                "${entry.name}\t${entry.name.removeSuffix("/")}\t${entry.size}"
            } + "\n",
            Charsets.UTF_8,
        )
    }
}
