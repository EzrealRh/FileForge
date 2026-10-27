package com.fileforge.core

import com.fileforge.core.pdf.PdfFormFills
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 表单填写规格的解析：换行/分号、值里带等号、复选框真值、缺等号的提示。 */
class PdfFormFillsTest {

    @Test
    fun `换行与分号都算分隔，值里可以再带等号`() {
        val fills = PdfFormFills.parse("name=Alice\ndate=2026-10-27;note=a=b")
        assertEquals(3, fills.size)
        assertEquals("name", fills[0].name)
        assertEquals("Alice", fills[0].value)
        assertEquals("2026-10-27", fills[1].value)
        assertEquals("a=b", fills[2].value, "按第一个等号切，后面的等号属于值")
    }

    @Test
    fun `空行忽略，字段名两侧空白去掉`() {
        val fills = PdfFormFills.parse("\n  name = Alice  \n\n")
        assertEquals(1, fills.size)
        assertEquals("name", fills[0].name)
        assertEquals("Alice", fills[0].value)
    }

    @Test
    fun `复选框真值只认几种写法`() {
        listOf("true", "YES", "on", "1", "是", "勾选", "checked").forEach {
            assertTrue(PdfFormFills.truthy(it), "$it 该算勾选")
        }
        listOf("false", "off", "0", "否", "", "maybe").forEach {
            assertEquals(false, PdfFormFills.truthy(it), "$it 不该算勾选")
        }
    }

    @Test
    fun `缺等号与空规格都有明确提示`() {
        assertTrue(PdfFormFills.problem("name")!!.contains("字段名=值"))
        assertNull(PdfFormFills.problem("name=Alice;agree=true"))
        assertTrue(PdfFormFills.problem("")!!.contains("先写"))
        assertTrue(PdfFormFills.problem(" =Alice")!!.contains("字段名"))
    }
}
