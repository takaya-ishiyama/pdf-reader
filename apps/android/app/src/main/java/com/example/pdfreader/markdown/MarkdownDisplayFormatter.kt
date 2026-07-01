package com.example.pdfreader.markdown

class MarkdownDisplayFormatter {
    fun format(markdown: String): String {
        val withoutCodeBlocks = markdown.replace(Regex("(?s)```.*?```"), "")
        val lines = mutableListOf<String>()
        withoutCodeBlocks
            .lineSequence()
            .forEach { line ->
                val formatted = formatLine(line)
                if (formatted.isBlank()) return@forEach
                lines += formatted
                if (isHeading(line)) lines += ""
            }
        return lines.joinToString("\n").trim()
    }

    private fun formatLine(line: String): String {
        return line
            .replace(Regex("^#{1,6}\\s*"), "")
            .replace(Regex("^\\s*[-*+]\\s+"), "")
            .replace(Regex("^\\s*>\\s?"), "")
            .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("__([^_]+)__"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .trimEnd()
    }

    private fun isHeading(line: String): Boolean =
        Regex("^#{1,6}\\s+.+$").matches(line.trim())
}

data class HeadingAnchor(
    val anchor: String,
    val title: String,
    val lineIndex: Int,
)

class MarkdownHeadingIndex {
    fun extractAnchors(markdown: String): List<HeadingAnchor> {
        val seen = mutableMapOf<String, Int>()
        return markdown
            .lineSequence()
            .withIndex()
            .mapNotNull { (index, line) ->
                val match = Regex("^#{1,6}\\s+(.+)$").find(line.trim()) ?: return@mapNotNull null
                val title = match.groupValues[1].trim()
                val baseAnchor = slug(title)
                val count = (seen[baseAnchor] ?: 0) + 1
                seen[baseAnchor] = count
                val anchor = if (count == 1) baseAnchor else "$baseAnchor-$count"
                HeadingAnchor(anchor, title, index)
            }
            .toList()
    }

    fun nearestAnchor(
        anchors: List<HeadingAnchor>,
        lineIndex: Int,
    ): String? =
        anchors
            .filter { it.lineIndex <= lineIndex }
            .maxByOrNull { it.lineIndex }
            ?.anchor

    private fun slug(value: String): String {
        val normalized = value
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
        return normalized.ifBlank { "section" }
    }
}
