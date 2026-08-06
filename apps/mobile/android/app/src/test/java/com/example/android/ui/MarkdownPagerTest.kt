package com.example.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPagerTest {
    @Test
    fun embeddedBase64ImagesAreSkippedWithoutEnteringRenderedPages() {
        val image = "![Image](data:image/png;base64," + "A".repeat(1_000_000) + ")"

        val pager = MarkdownPager("# Title\n$image\nBody", maxPageChars = 100)

        val rendered = (0 until pager.pageCount).joinToString("") { pager.page(it) }
        assertFalse(rendered.contains("base64"))
        assertTrue(rendered.contains("TITLE"))
        assertTrue(rendered.contains("Body"))
    }

    @Test
    fun renderedTextIsSplitIntoBoundedPages() {
        val pager = MarkdownPager("x".repeat(250), maxPageChars = 100)

        assertEquals(3, pager.pageCount)
        assertTrue((0 until pager.pageCount).all { pager.page(it).length <= 100 })
    }

    @Test
    fun overallProgressIncludesPageAndScrollPosition() {
        val pager = MarkdownPager("x".repeat(250), maxPageChars = 100)

        assertEquals(0.5, pager.progress(pageIndex = 1, pageProgress = 0.5), 0.001)
        assertEquals(1, pager.pageIndexForProgress(0.5))
    }
}
