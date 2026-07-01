package com.example.pdfreader.cache

import com.example.pdfreader.domain.ContentHash
import com.example.pdfreader.domain.DocumentId
import com.example.pdfreader.domain.DocumentVersion
import com.example.pdfreader.domain.ReadingProgress

data class MarkdownCacheKey(
    val documentId: DocumentId,
    val version: DocumentVersion,
    val contentHash: ContentHash,
) {
    fun stableName(): String =
        "${documentId.value}_${version.value}_${sanitize(contentHash.value)}"

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

interface MarkdownStorage {
    fun read(key: MarkdownCacheKey): String?
    fun write(key: MarkdownCacheKey, markdown: String)
}

data class CachedDocumentMetadata(
    val documentId: DocumentId,
    val title: String,
    val version: DocumentVersion,
    val contentHash: ContentHash,
    val signedUrl: String,
    val readingProgress: ReadingProgress?,
)

interface DocumentMetadataStorage {
    fun read(documentId: DocumentId): CachedDocumentMetadata?
    fun write(metadata: CachedDocumentMetadata)
}

class InMemoryMarkdownStorage : MarkdownStorage {
    private val values = mutableMapOf<MarkdownCacheKey, String>()

    override fun read(key: MarkdownCacheKey): String? = values[key]

    override fun write(key: MarkdownCacheKey, markdown: String) {
        values[key] = markdown
    }
}

class InMemoryDocumentMetadataStorage : DocumentMetadataStorage {
    private val values = mutableMapOf<DocumentId, CachedDocumentMetadata>()

    override fun read(documentId: DocumentId): CachedDocumentMetadata? = values[documentId]

    override fun write(metadata: CachedDocumentMetadata) {
        values[metadata.documentId] = metadata
    }
}
