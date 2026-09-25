package com.fileforge.converter.data

import com.fileforge.core.archive.ByteSlice
import java.io.File
import java.io.RandomAccessFile

/**
 * 按区间读磁盘上的文件，供 zip 目录与 OOXML 部件用。
 *
 * 放在 data 这一层是因为谁都可能要按区间读：引擎要，工作台给文件认类型也要。
 * 一次只取要的那段，所以几百 MB 的包不会因为读个目录就把堆吃满。
 */
internal class FileSlices(private val file: File) : ByteSlice, AutoCloseable {
    private val handle = RandomAccessFile(file, "r")

    override fun slice(from: Long, to: Long): ByteArray {
        val start = from.coerceAtLeast(0L)
        val stop = to.coerceAtMost(file.length())
        if (stop <= start) return ByteArray(0)
        val buffer = ByteArray((stop - start).toInt())
        return try {
            handle.seek(start)
            handle.readFully(buffer)
            buffer
        } catch (end: java.io.EOFException) {
            throw IllegalStateException("文件比它声称的短，读到第 ${start / 1024} KB 就没了", end)
        }
    }

    override fun close() {
        handle.close()
    }
}
