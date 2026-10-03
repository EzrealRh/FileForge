package com.fileforge.core

import com.fileforge.core.util.TimeInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TimeInputTest {

    @Test
    fun `纯秒与分秒与时分秒都读得出来`() {
        assertEquals(90.0, TimeInput.parseSeconds("90"))
        assertEquals(90.0, TimeInput.parseSeconds("1:30"))
        assertEquals(62.5, TimeInput.parseSeconds("1:02.5"))
        assertEquals(3600.0, TimeInput.parseSeconds("1:00:00"))
        assertEquals(90.0, TimeInput.parseSeconds(" 1:30 "))
    }

    @Test
    fun `读不出的写法给 null`() {
        assertNull(TimeInput.parseSeconds(""))
        assertNull(TimeInput.parseSeconds("abc"))
        assertNull(TimeInput.parseSeconds("-5"))
        assertNull(TimeInput.parseSeconds("1:2:3:4"))
    }

    @Test
    fun `多段起止按顺序展开`() {
        val ranges = TimeInput.parseRanges("5-12,30-41")
        assertEquals(listOf(5.0 to 12.0, 30.0 to 41.0), ranges)
    }

    @Test
    fun `止留空表示到片尾`() {
        assertEquals(listOf(5.0 to null), TimeInput.parseRanges("5-"))
        assertEquals(listOf(5.0 to null, 30.0 to 41.0), TimeInput.parseRanges("5-,30-41"))
    }

    @Test
    fun `分秒写法在多段里也算数`() {
        assertEquals(listOf(65.0 to 150.0), TimeInput.parseRanges("1:05-2:30"))
    }

    @Test
    fun `段给反了或读不出的丢掉`() {
        assertEquals(emptyList(), TimeInput.parseRanges("12-5"))
        assertEquals(emptyList(), TimeInput.parseRanges("abc"))
        assertEquals(emptyList(), TimeInput.parseRanges(""))
    }
}
