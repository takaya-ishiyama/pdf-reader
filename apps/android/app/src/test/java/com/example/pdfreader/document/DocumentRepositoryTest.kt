package com.example.pdfreader.document

import com.example.pdfreader.api.DocumentApiClient
import com.example.pdfreader.api.DocumentDetailDto
import com.example.pdfreader.api.DocumentSummaryDto
import com.example.pdfreader.api.ReadingProgressDto
import com.example.pdfreader.api.UpdateReadingProgressRequest
import com.example.pdfreader.cache.InMemoryDocumentMetadataStorage
import com.example.pdfreader.cache.InMemoryMarkdownStorage
import com.example.pdfreader.domain.DocumentId
import com.example.pdfreader.domain.DocumentVersion
import com.example.pdfreader.domain.PositionType
import com.example.pdfreader.domain.ReadingProgress
import com.example.pdfreader.progress.Clock
import com.example.pdfreader.progress.ProgressApi
import com.example.pdfreader.progress.ProgressSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRepositoryTest {
    @Test
    fun listDocumentsMapsApiSummaries() {
        val api = FakeDocumentApiClient()
        val repository = DocumentRepository(
            api = api,
            markdownDownloader = FakeMarkdownDownloader(),
            markdownStorage = InMemoryMarkdownStorage(),
            progressSyncManager = ProgressSyncManager(FakeProgressApi(), FakeClock()),
        )
        api.summaries = listOf(
            DocumentSummaryDto(
                documentId = "doc-1",
                title = "First",
                version = 2,
                progressRatio = 0.5,
                updatedAt = "2026-06-29T00:00:00Z",
            ),
        )

        val documents = repository.listDocuments("client-1")

        assertEquals(1, documents.size)
        assertEquals("doc-1", documents.single().documentId.value)
        assertEquals("First", documents.single().title)
        assertEquals(2, documents.single().version.value)
        assertEquals(0.5, documents.single().progressRatio ?: -1.0, 0.0)
        assertEquals("client-1", api.listClientId)
    }

    @Test
    fun loadDocumentUsesLocalMarkdownCacheWhenVersionAndHashMatch() {
        val api = FakeDocumentApiClient()
        val downloader = FakeMarkdownDownloader()
        val storage = InMemoryMarkdownStorage()
        val repository = DocumentRepository(
            api = api,
            markdownDownloader = downloader,
            markdownStorage = storage,
            progressSyncManager = ProgressSyncManager(FakeProgressApi(), FakeClock()),
        )
        val cached = repository.cacheDocument(
            documentId = "doc-1",
            version = 2,
            contentHash = "sha256:abc",
            markdown = "# Cached",
        )

        api.detail = detail(version = 2, contentHash = "sha256:abc")

        val loaded = repository.loadDocument("doc-1", "client-1")

        assertEquals("# Cached", loaded.markdown)
        assertEquals(cached, loaded.cacheKey)
        assertEquals(0, downloader.requestedUrls.size)
    }

    @Test
    fun loadDocumentDownloadsAndCachesMarkdownWhenCacheMisses() {
        val api = FakeDocumentApiClient()
        val downloader = FakeMarkdownDownloader()
        val storage = InMemoryMarkdownStorage()
        val metadataStorage = InMemoryDocumentMetadataStorage()
        val repository = DocumentRepository(
            api = api,
            markdownDownloader = downloader,
            markdownStorage = storage,
            progressSyncManager = ProgressSyncManager(FakeProgressApi(), FakeClock()),
            metadataStorage = metadataStorage,
        )
        api.detail = detail(version = 3, contentHash = "sha256:new")
        downloader.responses["https://signed.example/doc.md"] = "# Downloaded"

        val loaded = repository.loadDocument("doc-1", "client-1")

        assertEquals("# Downloaded", loaded.markdown)
        assertEquals(listOf("https://signed.example/doc.md"), downloader.requestedUrls)
        assertEquals("# Downloaded", storage.read(loaded.cacheKey))
        assertEquals("Document", metadataStorage.read(DocumentId("doc-1"))?.title)
    }

    @Test
    fun loadDocumentFallsBackToCachedMetadataAndMarkdownWhenApiFails() {
        val api = FakeDocumentApiClient()
        val downloader = FakeMarkdownDownloader()
        val storage = InMemoryMarkdownStorage()
        val metadataStorage = InMemoryDocumentMetadataStorage()
        val repository = DocumentRepository(
            api = api,
            markdownDownloader = downloader,
            markdownStorage = storage,
            progressSyncManager = ProgressSyncManager(FakeProgressApi(), FakeClock()),
            metadataStorage = metadataStorage,
        )
        api.detail = detail(version = 3, contentHash = "sha256:new")
        downloader.responses["https://signed.example/doc.md"] = "# Cached"
        repository.loadDocument("doc-1", "client-1")

        api.failure = IllegalStateException("network unavailable")

        val loaded = repository.loadDocument("doc-1", "client-1")

        assertEquals("# Cached", loaded.markdown)
        assertEquals("Document", loaded.detail.title)
        assertEquals(3, loaded.detail.version.value)
    }

    @Test
    fun loadDocumentFallsBackToConsistentCachedDocumentWhenNewDownloadFails() {
        val api = FakeDocumentApiClient()
        val downloader = FakeMarkdownDownloader()
        val storage = InMemoryMarkdownStorage()
        val metadataStorage = InMemoryDocumentMetadataStorage()
        val repository = DocumentRepository(
            api = api,
            markdownDownloader = downloader,
            markdownStorage = storage,
            progressSyncManager = ProgressSyncManager(FakeProgressApi(), FakeClock()),
            metadataStorage = metadataStorage,
        )
        api.detail = detail(version = 2, contentHash = "sha256:old")
        downloader.responses["https://signed.example/doc.md"] = "# Old"
        repository.loadDocument("doc-1", "client-1")
        api.detail = detail(version = 3, contentHash = "sha256:new")
        downloader.responses.clear()

        val loaded = repository.loadDocument("doc-1", "client-1")

        assertEquals("# Old", loaded.markdown)
        assertEquals(2, loaded.detail.version.value)
        assertEquals("sha256:old", loaded.detail.contentHash.value)
    }

    @Test
    fun updateProgressPassesDomainProgressToSyncManager() {
        val progressApi = FakeProgressApi()
        val clock = FakeClock()
        val repository = DocumentRepository(
            api = FakeDocumentApiClient(),
            markdownDownloader = FakeMarkdownDownloader(),
            markdownStorage = InMemoryMarkdownStorage(),
            progressSyncManager = ProgressSyncManager(progressApi, clock),
        )

        repository.onReadingProgressChanged(
            documentId = "doc-1",
            version = 1,
            positionType = "heading_anchor",
            positionValue = "chapter-1",
            progressRatio = 0.4,
        )
        clock.advance(3_000)
        repository.tickProgressSync()

        assertEquals(1, progressApi.sent.size)
        assertEquals("chapter-1", progressApi.sent.single().positionValue)
        assertEquals(0.4, progressApi.sent.single().progressRatio, 0.0)
    }

    @Test
    fun viewModelLoadsDocumentAndExposesContentState() {
        val api = FakeDocumentApiClient()
        val downloader = FakeMarkdownDownloader()
        val viewModel = MarkdownViewerViewModel(
            repository = DocumentRepository(
                api = api,
                markdownDownloader = downloader,
                markdownStorage = InMemoryMarkdownStorage(),
                progressSyncManager = ProgressSyncManager(FakeProgressApi(), FakeClock()),
            ),
        )
        api.detail = detail(
            version = 1,
            contentHash = "sha256:vm",
            progress = ReadingProgressDto(
                positionType = "heading_anchor",
                positionValue = "chapter-2",
                progressRatio = 0.6,
            ),
        )
        downloader.responses["https://signed.example/doc.md"] = "# ViewModel"

        viewModel.load("doc-1", "client-1")

        val state = viewModel.state
        assertTrue(state is MarkdownViewerState.Content)
        state as MarkdownViewerState.Content
        assertEquals("# ViewModel", state.markdown)
        assertEquals("chapter-2", state.readingProgress?.positionValue)
    }

    @Test
    fun viewModelCanExposeCachedDocumentBeforeNetworkRefresh() {
        val api = FakeDocumentApiClient()
        val downloader = FakeMarkdownDownloader()
        val storage = InMemoryMarkdownStorage()
        val metadataStorage = InMemoryDocumentMetadataStorage()
        val repository = DocumentRepository(
            api = api,
            markdownDownloader = downloader,
            markdownStorage = storage,
            progressSyncManager = ProgressSyncManager(FakeProgressApi(), FakeClock()),
            metadataStorage = metadataStorage,
        )
        api.detail = detail(version = 1, contentHash = "sha256:cached")
        downloader.responses["https://signed.example/doc.md"] = "# Cached"
        repository.loadDocument("doc-1", "client-1")
        val viewModel = MarkdownViewerViewModel(repository)

        val loaded = viewModel.loadCached("doc-1")

        assertTrue(loaded)
        val state = viewModel.state
        assertTrue(state is MarkdownViewerState.Content)
        state as MarkdownViewerState.Content
        assertEquals("# Cached", state.markdown)
    }

    @Test
    fun viewModelLoadsDocumentListAndExposesListState() {
        val api = FakeDocumentApiClient()
        val viewModel = MarkdownViewerViewModel(
            repository = DocumentRepository(
                api = api,
                markdownDownloader = FakeMarkdownDownloader(),
                markdownStorage = InMemoryMarkdownStorage(),
                progressSyncManager = ProgressSyncManager(FakeProgressApi(), FakeClock()),
            ),
        )
        api.summaries = listOf(
            DocumentSummaryDto(
                documentId = "doc-1",
                title = "First",
                version = 1,
                progressRatio = null,
                updatedAt = null,
            ),
        )

        viewModel.loadDocuments("client-1")

        val state = viewModel.state
        assertTrue(state is MarkdownViewerState.DocumentList)
        state as MarkdownViewerState.DocumentList
        assertEquals("First", state.documents.single().title)
    }


    private fun detail(
        version: Int,
        contentHash: String,
        progress: ReadingProgressDto? = null,
    ): DocumentDetailDto =
        DocumentDetailDto(
            documentId = "doc-1",
            title = "Document",
            version = version,
            contentHash = contentHash,
            signedUrl = "https://signed.example/doc.md",
            signedUrlExpiresAt = "2026-06-29T01:00:00Z",
            readingProgress = progress,
        )
}

private class FakeDocumentApiClient : DocumentApiClient {
    lateinit var detail: DocumentDetailDto
    var summaries: List<DocumentSummaryDto> = emptyList()
    var listClientId: String? = null
    var failure: RuntimeException? = null

    override fun listDocuments(clientId: String): List<DocumentSummaryDto> {
        failure?.let { throw it }
        listClientId = clientId
        return summaries
    }

    override fun getDocument(documentId: String, clientId: String): DocumentDetailDto {
        failure?.let { throw it }
        return detail
    }

    override fun updateReadingProgress(
        documentId: String,
        request: UpdateReadingProgressRequest,
    ) {
    }
}

private class FakeMarkdownDownloader : MarkdownDownloader {
    val responses = mutableMapOf<String, String>()
    val requestedUrls = mutableListOf<String>()

    override fun download(url: String): String {
        requestedUrls += url
        return responses.getValue(url)
    }
}

private class FakeProgressApi : ProgressApi {
    val sent = mutableListOf<ReadingProgress>()

    override fun update(progress: ReadingProgress) {
        sent += progress
    }
}

private class FakeClock : Clock {
    private var now = 0L

    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}
