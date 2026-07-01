package com.example.pdfreader.cache

import com.example.pdfreader.domain.ContentHash
import com.example.pdfreader.domain.DocumentId
import com.example.pdfreader.domain.DocumentVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class FileMarkdownStorageTest {
    @Test
    fun returnsNullWhenCachedMarkdownDoesNotExist() {
        val storage = FileMarkdownStorage(tempDirectory())

        assertNull(storage.read(key()))
    }

    @Test
    fun writesAndReadsMarkdownByStableCacheFileName() {
        val directory = tempDirectory()
        val storage = FileMarkdownStorage(directory)

        storage.write(key(), "# Cached")

        assertEquals("# Cached", storage.read(key()))
        assertEquals(
            listOf("doc-1_2_sha256_abc.md"),
            directory.list()?.toList()?.sorted(),
        )
    }

    private fun key(): MarkdownCacheKey =
        MarkdownCacheKey(
            documentId = DocumentId("doc-1"),
            version = DocumentVersion(2),
            contentHash = ContentHash("sha256:abc"),
        )

    private fun tempDirectory(): File =
        createTempFile(prefix = "markdown-cache", suffix = "").also { file ->
            file.delete()
            file.mkdirs()
            file.deleteOnExit()
        }
}
