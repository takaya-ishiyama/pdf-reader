package com.example.android.data

import java.io.File
import java.security.MessageDigest

class FileMarkdownCache(private val cacheDir: File) : MarkdownCache {
    override fun key(documentId: String, version: Int, contentHash: String): String =
        "$documentId-$version-$contentHash"

    override fun read(key: String): String? {
        val file = fileFor(key)
        return if (file.exists()) file.readText() else null
    }

    override fun write(key: String, markdown: String) {
        cacheDir.mkdirs()
        fileFor(key).writeText(markdown)
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$digest.md")
    }
}
