package com.example.pdfreader.cache

import java.io.File

class FileMarkdownStorage(
    private val directory: File,
) : MarkdownStorage {
    override fun read(key: MarkdownCacheKey): String? {
        val file = fileFor(key)
        if (!file.exists()) return null
        return file.readText()
    }

    override fun write(key: MarkdownCacheKey, markdown: String) {
        directory.mkdirs()
        fileFor(key).writeText(markdown)
    }

    private fun fileFor(key: MarkdownCacheKey): File =
        File(directory, "${key.stableName()}.md")
}
