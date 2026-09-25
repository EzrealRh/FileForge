package com.fileforge.core

import com.fileforge.core.pdf.PdfPermission
import com.fileforge.core.pdf.PdfSecurity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 加密码的参数规矩。为什么值得单测：这几条每条都对应一种"用户以为自己被保护了、
 * 其实没有"或者"以后自己解不开"的下场，而这些在产物上看不出来。
 * 行为依据来自实测：`tools/pdfprobe/run.sh security`。
 */
class PdfSecurityTest {

    private val some = setOf(PdfPermission.Print, PdfPermission.Copy)

    @Test
    fun `两个密码都没填就不动手`() {
        assertNotNull(PdfSecurity.validate("", "", some))
        assertNotNull(PdfSecurity.validate("   ", "", some))
    }

    @Test
    fun `短密码拦下来而不是默默产出一个假保险`() {
        assertNotNull(PdfSecurity.validate("12", "", some))
        assertNotNull(PdfSecurity.validate("", "abc", some))
        assertNull(PdfSecurity.validate("abcd", "", some))
    }

    @Test
    fun `四项限制全放开等于没加密`() {
        assertNotNull(PdfSecurity.validate("abcd", "", PdfPermission.entries.toSet()))
        assertNull(PdfSecurity.validate("abcd", "", emptySet()))
    }

    @Test
    fun `所有者密码留空必须照抄打开密码`() {
        // PDFBox 在所有者密码为空时会生成一个随机值：用户看不见、记不住，
        // 于是这份文件以后连自己都解不开。实现上绝不允许产出那种状态。
        assertEquals("abcd", PdfSecurity.ownerPasswordFor("", "abcd"))
        assertEquals("only-owner", PdfSecurity.ownerPasswordFor("only-owner", "abcd"))
    }

    @Test
    fun `只设限制不设打开密码是允许的组合`() {
        // 这种"能打开但不给打印/复制"的用法是真的有人要，不能当成错误
        assertNull(PdfSecurity.validate("", "owner-pw", setOf(PdfPermission.Print)))
        assertEquals("", PdfSecurity.userPasswordOf(""))
    }

    @Test
    fun `结果说明讲清限制了什么`() {
        // 集合里放的是"还允许"的权限，所以说明要报的是反面
        assertEquals("不让：修改内容、填表单", PdfSecurity.summarize(some))
        assertEquals("不让：打印、复制文字、修改内容、填表单", PdfSecurity.summarize(emptySet()))
        assertTrue(PdfSecurity.summarize(PdfPermission.entries.toSet()).contains("全放开"))
    }

    @Test
    fun `权限位不是锁这件事必须写在话术里`() {
        // 实测：勾了「不允许复制」，PDFBox 的抽取器照样抽得出全部文字。
        // 所以界面上的说明不能把它讲成一道锁 —— 这条断言钉的是文案存在。
        assertTrue(PdfSecurity.ADVISORY_NOTE.contains("不是加密锁"))
        assertTrue(PdfPermission.Copy.note.contains("拦不住"))
    }
}
