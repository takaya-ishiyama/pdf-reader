package com.example.pdfreader.cache

import com.example.pdfreader.domain.ContentHash
import com.example.pdfreader.domain.DocumentId
import com.example.pdfreader.domain.DocumentVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownCacheKeyTest {
    @Test
    fun stableNameUsesDocumentIdVersionAndContentHash() {
        val key = MarkdownCacheKey(
            documentId = DocumentId("doc-1"),
            version = DocumentVersion(3),
            contentHash = ContentHash("sha256:abc"),
        )

        assertEquals("doc-1_3_sha256_abc", key.stableName())
    }

    @Test
    fun inMemoryStorageReadsWrittenMarkdownByFullCacheKey() {
        val storage = InMemoryMarkdownStorage()
        val version1 = MarkdownCacheKey(DocumentId("doc-1"), DocumentVersion(1), ContentHash("hash-a"))
        val version2 = MarkdownCacheKey(DocumentId("doc-1"), DocumentVersion(2), ContentHash("hash-b"))

        storage.write(version1, "# Old")
        storage.write(version2, "# New")

        assertEquals("# Old", storage.read(version1))
        assertEquals("# New", storage.read(version2))
    }
}
