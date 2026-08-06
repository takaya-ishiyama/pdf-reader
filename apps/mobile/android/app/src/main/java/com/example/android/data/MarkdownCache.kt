package com.example.android.data

interface MarkdownCache {
    fun key(documentId: String, version: Int, contentHash: String): String
    fun read(key: String): String?
    fun write(key: String, markdown: String)
}
