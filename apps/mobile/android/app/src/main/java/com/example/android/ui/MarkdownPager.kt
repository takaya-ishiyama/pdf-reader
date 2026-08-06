package com.example.android.ui

class MarkdownPager(
    markdown: String,
    private val maxPageChars: Int = DEFAULT_MAX_PAGE_CHARS,
) {
    private val pages: List<String> = buildPages(markdown)

    val pageCount: Int get() = pages.size

    fun page(index: Int): String = pages[index.coerceIn(0, pages.lastIndex)]

    fun pageIndexForProgress(progress: Double): Int =
        (progress.coerceIn(0.0, 1.0) * pageCount).toInt().coerceAtMost(pages.lastIndex)

    fun progress(pageIndex: Int, pageProgress: Double): Double =
        ((pageIndex.coerceIn(0, pages.lastIndex) + pageProgress.coerceIn(0.0, 1.0)) / pageCount)
            .coerceIn(0.0, 1.0)

    private fun buildPages(markdown: String): List<String> {
        val result = mutableListOf<String>()
        var page = StringBuilder(maxPageChars)
        var lineStart = 0

        while (lineStart <= markdown.length) {
            val newline = markdown.indexOf('\n', lineStart)
            val lineEnd = if (newline == -1) markdown.length else newline
            if (!isEmbeddedImage(markdown, lineStart, lineEnd)) {
                val headingLevel = headingLevel(markdown, lineStart, lineEnd)
                val contentStart = if (headingLevel == 1 || headingLevel == 2) lineStart + headingLevel + 1 else lineStart
                val uppercase = headingLevel == 1
                if (headingLevel > 0) appendText("\n", page, result)
                appendRange(markdown, contentStart, lineEnd, uppercase, page, result)
                appendText(if (headingLevel > 0) "\n\n" else "\n", page, result)
            }
            if (newline == -1) break
            lineStart = newline + 1
        }

        flush(page, result)
        return result.ifEmpty { listOf("") }
    }

    private fun appendRange(
        source: String,
        start: Int,
        end: Int,
        uppercase: Boolean,
        initialPage: StringBuilder,
        result: MutableList<String>,
    ) {
        var page = initialPage
        var offset = start
        while (offset < end) {
            if (page.length == maxPageChars) {
                flush(page, result)
                page = initialPage
            }
            val chunkEnd = minOf(end, offset + maxPageChars - page.length)
            if (uppercase) {
                page.append(source.substring(offset, chunkEnd).uppercase())
            } else {
                page.append(source, offset, chunkEnd)
            }
            offset = chunkEnd
        }
    }

    private fun appendText(text: String, page: StringBuilder, result: MutableList<String>) {
        var offset = 0
        while (offset < text.length) {
            if (page.length == maxPageChars) flush(page, result)
            val end = minOf(text.length, offset + maxPageChars - page.length)
            page.append(text, offset, end)
            offset = end
        }
    }

    private fun flush(page: StringBuilder, result: MutableList<String>) {
        if (page.isNotEmpty()) {
            result += page.toString()
            page.setLength(0)
        }
    }

    private fun isEmbeddedImage(source: String, start: Int, end: Int): Boolean {
        var contentStart = start
        while (contentStart < end && source[contentStart].isWhitespace()) contentStart++
        return source.regionMatches(contentStart, "![", 0, 2) &&
            source.indexOf("](data:image/", contentStart).let { it in contentStart until end }
    }

    private fun headingLevel(source: String, start: Int, end: Int): Int = when {
        end - start >= 3 && source.regionMatches(start, "## ", 0, 3) -> 2
        end - start >= 2 && source.regionMatches(start, "# ", 0, 2) -> 1
        else -> 0
    }

    companion object {
        const val DEFAULT_MAX_PAGE_CHARS = 100_000
    }
}
