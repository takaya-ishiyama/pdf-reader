package com.example.android.ui

import com.example.android.data.ApiClient
import com.example.android.data.CacheControl
import com.example.android.data.ClientIdProvider
import com.example.android.data.DocumentDetail
import com.example.android.data.DocumentRepository
import com.example.android.data.DocumentSummary
import com.example.android.data.MarkdownCache
import com.example.android.data.MarkdownFetcher
import com.example.android.data.MetadataStore
import com.example.android.data.ProgressUpdate
import com.example.android.data.ProgressUpdateResult
import com.example.android.data.ResultState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentReaderViewModelTest {
    @Test
    fun loadDocumentsPublishesLoadedState() = runTest {
        val summary = DocumentSummary("doc-1", "Sample", 1, 0.4, null)
        val viewModel = viewModel(api = ViewModelApiClient(documents = listOf(summary)))

        viewModel.loadDocuments()
        runCurrent()

        assertEquals(ResultState.Loaded(listOf(summary)), viewModel.documents.value)
    }

    @Test
    fun openDocumentPublishesMarkdownDocument() = runTest {
        val cache = ViewModelMarkdownCache()
        val detail = detail()
        val viewModel = viewModel(
            api = ViewModelApiClient(detail = detail),
            fetcher = object : MarkdownFetcher {
                override suspend fun fetch(url: String): String = "# Loaded"
            },
            cache = cache,
        )

        viewModel.openDocument("doc-1")
        runCurrent()

        val state = viewModel.document.value
        assertTrue(state is ResultState.Loaded)
        assertEquals("# Loaded", (state as ResultState.Loaded).value.markdown)
    }

    private fun TestScope.viewModel(
        api: ViewModelApiClient = ViewModelApiClient(),
        fetcher: MarkdownFetcher = object : MarkdownFetcher {
            override suspend fun fetch(url: String): String = ""
        },
        cache: MarkdownCache = ViewModelMarkdownCache(),
    ) = DocumentReaderViewModel(
        repository = DocumentRepository(
            apiClient = api,
            markdownFetcher = fetcher,
            markdownCache = cache,
            metadataStore = ViewModelMetadataStore(),
            clientIdProvider = object : ClientIdProvider {
                override fun getOrCreateClientId(): String = "client"
            },
        ),
        scope = this,
    )

    private fun detail() = DocumentDetail(
        documentId = "doc-1",
        title = "Sample",
        version = 1,
        contentHash = "sha256:1",
        signedUrl = "https://signed.example/content.md",
        signedUrlExpiresAt = Instant.parse("2026-06-29T01:00:00Z"),
        cacheControl = CacheControl(86400, 604800),
        readingProgress = null,
    )
}

private class ViewModelApiClient(
    private val documents: List<DocumentSummary> = emptyList(),
    private val detail: DocumentDetail = DocumentDetail(
        documentId = "doc-1",
        title = "Sample",
        version = 1,
        contentHash = "sha256:1",
        signedUrl = "https://signed.example/content.md",
        signedUrlExpiresAt = Instant.parse("2026-06-29T01:00:00Z"),
        cacheControl = CacheControl(86400, 604800),
        readingProgress = null,
    ),
) : ApiClient {
    override suspend fun listDocuments(clientId: String): List<DocumentSummary> = documents
    override suspend fun getDocument(documentId: String, clientId: String): DocumentDetail = detail
    override suspend fun updateProgress(clientId: String, update: ProgressUpdate): ProgressUpdateResult =
        ProgressUpdateResult(update.documentId, update.version, Instant.parse("2026-06-29T00:00:00Z"))
}

private class ViewModelMarkdownCache : MarkdownCache {
    private val values = mutableMapOf<String, String>()

    override fun key(documentId: String, version: Int, contentHash: String): String =
        "$documentId-$version-$contentHash"

    override fun read(key: String): String? = values[key]

    override fun write(key: String, markdown: String) {
        values[key] = markdown
    }
}

private class ViewModelMetadataStore : MetadataStore {
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
