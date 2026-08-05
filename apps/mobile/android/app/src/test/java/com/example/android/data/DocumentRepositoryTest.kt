package com.example.android.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DocumentRepositoryTest {
    @Test
    fun markdownCacheKeyUsesDocumentVersionAndContentHash() = runTest {
        val cache = InMemoryMarkdownCache()
        val repository = repository(cache = cache)
        val detail = detail(contentHash = "sha256:abc", version = 3)

        assertEquals("doc-1-3-sha256:abc", repository.cacheKey(detail))
    }

    @Test
    fun loadDocumentFetchesSignedUrlAndStoresMarkdownByFreshCacheKey() = runTest {
        val api = FakeApiClient(detail = detail(signedUrl = "https://signed.example/content.md"))
        val fetcher = FakeMarkdownFetcher(markdown = "# Chapter\nBody")
        val cache = InMemoryMarkdownCache()
        val repository = repository(api = api, fetcher = fetcher, cache = cache)

        val document = repository.loadDocument("doc-1")

        assertFalse(document.fromCache)
        assertEquals("# Chapter\nBody", document.markdown)
        assertEquals("https://signed.example/content.md", fetcher.requestedUrl)
        assertEquals("# Chapter\nBody", cache.read("doc-1-1-sha256:1"))
    }

    @Test
    fun loadDocumentFallsBackToCachedDetailAndMarkdownWhenApiFails() = runTest {
        val store = InMemoryMetadataStore()
        val cache = InMemoryMarkdownCache()
        val cachedDetail = detail(contentHash = "sha256:cached")
        store.saveDetail(cachedDetail)
        cache.write("doc-1-1-sha256:cached", "cached body")
        val repository = repository(api = FakeApiClient(fail = true), store = store, cache = cache)

        val document = repository.loadDocument("doc-1")

        assertTrue(document.fromCache)
        assertEquals("cached body", document.markdown)
    }

    @Test
    fun updateProgressQueuesOfflineEventWhenApiFails() = runTest {
        val store = InMemoryMetadataStore()
        val repository = repository(api = FakeApiClient(fail = true), store = store)
        val update = progress()

        val sent = repository.updateProgress(update)

        assertFalse(sent)
        assertEquals(listOf(update), store.loadProgressQueue())
    }

    @Test
    fun flushQueuedProgressSendsAndClearsQueue() = runTest {
        val store = InMemoryMetadataStore()
        val api = FakeApiClient()
        val update = progress()
        store.enqueueProgress(update)
        val repository = repository(api = api, store = store)

        val count = repository.flushQueuedProgress()

        assertEquals(1, count)
        assertEquals(listOf(update), api.progressUpdates)
        assertTrue(store.loadProgressQueue().isEmpty())
    }

    private fun repository(
        api: FakeApiClient = FakeApiClient(),
        fetcher: FakeMarkdownFetcher = FakeMarkdownFetcher(),
        cache: InMemoryMarkdownCache = InMemoryMarkdownCache(),
        store: InMemoryMetadataStore = InMemoryMetadataStore(),
    ) = DocumentRepository(
        apiClient = api,
        markdownFetcher = fetcher,
        markdownCache = cache,
        metadataStore = store,
        clientIdProvider = object : ClientIdProvider {
            override fun getOrCreateClientId(): String = "client-1"
        },
    )

    private fun detail(
        version: Int = 1,
        contentHash: String = "sha256:1",
        signedUrl: String = "https://signed.example/default.md",
    ) = DocumentDetail(
        documentId = "doc-1",
        title = "Sample",
        version = version,
        contentHash = contentHash,
        signedUrl = signedUrl,
        signedUrlExpiresAt = Instant.parse("2026-06-29T01:00:00Z"),
        cacheControl = CacheControl(86400, 604800),
        readingProgress = null,
    )

    private fun progress() = ProgressUpdate(
        documentId = "doc-1",
        version = 1,
        positionType = PositionType.HeadingAnchor,
        positionValue = "chapter-1",
        progressRatio = 0.25,
    )
}

private class FakeApiClient(
    private val detail: DocumentDetail = DocumentDetail(
        documentId = "doc-1",
        title = "Sample",
        version = 1,
        contentHash = "sha256:1",
        signedUrl = "https://signed.example/default.md",
        signedUrlExpiresAt = Instant.parse("2026-06-29T01:00:00Z"),
        cacheControl = CacheControl(86400, 604800),
        readingProgress = null,
    ),
    private val fail: Boolean = false,
) : ApiClient {
    val progressUpdates = mutableListOf<ProgressUpdate>()

    override suspend fun listDocuments(clientId: String): List<DocumentSummary> {
        if (fail) error("offline")
        return listOf(DocumentSummary("doc-1", "Sample", 1, 0.0, null))
    }

    override suspend fun getDocument(documentId: String, clientId: String): DocumentDetail {
        if (fail) error("offline")
        return detail
    }

    override suspend fun updateProgress(clientId: String, update: ProgressUpdate): ProgressUpdateResult {
        if (fail) error("offline")
        progressUpdates += update
        return ProgressUpdateResult(update.documentId, update.version, Instant.parse("2026-06-29T00:00:00Z"))
    }
}

private class FakeMarkdownFetcher(
    private val markdown: String = "body",
) : MarkdownFetcher {
    var requestedUrl: String? = null

    override suspend fun fetch(url: String): String {
        requestedUrl = url
        return markdown
    }
}

private class InMemoryMarkdownCache : MarkdownCache {
    private val values = mutableMapOf<String, String>()

    override fun key(documentId: String, version: Int, contentHash: String): String =
        "$documentId-$version-$contentHash"

    override fun read(key: String): String? = values[key]

    override fun write(key: String, markdown: String) {
        values[key] = markdown
    }
}

private class InMemoryMetadataStore : MetadataStore {
    private var documents = emptyList<DocumentSummary>()
    private val details = mutableMapOf<String, DocumentDetail>()
    private var queue = emptyList<ProgressUpdate>()

    override fun saveDocuments(documents: List<DocumentSummary>) {
        this.documents = documents
    }

    override fun loadDocuments(): List<DocumentSummary> = documents

    override fun saveDetail(detail: DocumentDetail) {
        details[detail.documentId] = detail
    }

    override fun loadDetail(documentId: String): DocumentDetail? = details[documentId]

    override fun enqueueProgress(update: ProgressUpdate) {
        queue = queue + update
    }

    override fun loadProgressQueue(): List<ProgressUpdate> = queue

    override fun replaceProgressQueue(updates: List<ProgressUpdate>) {
        queue = updates
    }
}
