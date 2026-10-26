package com.fileforge.core

import com.fileforge.core.video.TrimPreview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 视频截取面板的预览起点：只认第一段，段给反了不预览。 */
class TrimPreviewTest {

    @Test
    fun `预览用的是第一段的起点`() {
        assertEquals(5_000_000L, TrimPreview.firstStartUs("5-12"))
        assertEquals(65_000_000L, TrimPreview.firstStartUs("1:05-2:30,40-50"))
        assertEquals(500_000L, TrimPreview.firstStartUs("0.5-1"))
        assertEquals(0L, TrimPreview.firstStartUs("0-3"))
    }

    @Test
    fun `没写或写反了不预览`() {
        assertNull(TrimPreview.firstStartUs(""))
        assertNull(TrimPreview.firstStartUs("12-5"))
        assertNull(TrimPreview.firstStartUs("5-5"))
        assertNull(TrimPreview.firstStartUs("abc"))
    }
}
