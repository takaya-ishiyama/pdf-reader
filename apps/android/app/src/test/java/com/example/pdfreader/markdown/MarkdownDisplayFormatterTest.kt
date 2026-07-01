package com.example.pdfreader.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownDisplayFormatterTest {
    @Test
    fun formatsCommonMarkdownForPlainTextDisplay() {
        val markdown = """
            # Chapter 1
            Read [OpenAI](https://openai.com).
            ![diagram](image.png)
            ```kotlin
            println("skip")
            ```
            - item
        """.trimIndent()

        val formatted = MarkdownDisplayFormatter().format(markdown)

        assertEquals(
            """
            Chapter 1
            
            Read OpenAI.
            diagram
            item
            """.trimIndent(),
            formatted,
        )
    }

    @Test
    fun createsStableHeadingAnchors() {
        val anchors = MarkdownHeadingIndex().extractAnchors(
            """
            # Chapter 1
            body
            ## Chapter 1
            ### What's next?
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                HeadingAnchor("chapter-1", "Chapter 1", 0),
                HeadingAnchor("chapter-1-2", "Chapter 1", 2),
                HeadingAnchor("whats-next", "What's next?", 3),
            ),
            anchors,
        )
    }

    @Test
    fun findsNearestHeadingAnchorForDisplayedLine() {
        val anchors = listOf(
            HeadingAnchor("intro", "Intro", 0),
            HeadingAnchor("details", "Details", 4),
        )

        assertEquals("intro", MarkdownHeadingIndex().nearestAnchor(anchors, 2))
        assertEquals("details", MarkdownHeadingIndex().nearestAnchor(anchors, 5))
    }
}
