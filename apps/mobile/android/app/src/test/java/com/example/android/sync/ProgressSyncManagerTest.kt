package com.example.android.sync

import com.example.android.data.ApiClient
import com.example.android.data.CacheControl
import com.example.android.data.ClientIdProvider
import com.example.android.data.DocumentDetail
import com.example.android.data.DocumentRepository
import com.example.android.data.DocumentSummary
import com.example.android.data.MarkdownCache
import com.example.android.data.MarkdownFetcher
import com.example.android.data.MetadataStore
import com.example.android.data.PositionType
import com.example.android.data.ProgressUpdate
import com.example.android.data.ProgressUpdateResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressSyncManagerTest {
    @Test
    fun debouncesScrollProgressBeforeSending() = runTest {
        val api = CapturingApiClient()
        val manager = manager(api)

        manager.onPositionChanged(progress("line-1"))
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(0, api.updates.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(progress("line-1")), api.updates)
    }

    @Test
    fun sendsImmediatelyWhenHeadingAnchorChanges() = runTest {
        val api = CapturingApiClient()
        val manager = manager(api)

        manager.onPositionChanged(progress("chapter-1", PositionType.HeadingAnchor))
        manager.onPositionChanged(progress("chapter-2", PositionType.HeadingAnchor))
        runCurrent()

        assertEquals(listOf(progress("chapter-2", PositionType.HeadingAnchor)), api.updates)
    }

    @Test
    fun flushSendsPendingProgressAndQueuedEvents() = runTest {
        val api = CapturingApiClient()
        val store = QueueStore().apply { enqueueProgress(progress("queued")) }
        val manager = manager(api, store)

        manager.onPositionChanged(progress("pending"))
        manager.flush()
        runCurrent()

        assertEquals(listOf(progress("pending"), progress("queued")), api.updates)
        assertEquals(emptyList<ProgressUpdate>(), store.loadProgressQueue())
    }

    private fun TestScope.manager(
        api: CapturingApiClient,
        store: MetadataStore = QueueStore(),
    ) = ProgressSyncManager(
        repository = DocumentRepository(
            apiClient = api,
            markdownFetcher = object : MarkdownFetcher {
                override suspend fun fetch(url: String): String = ""
            },
            markdownCache = object : MarkdownCache {
                override fun key(documentId: String, version: Int, contentHash: String): String = ""
                override fun read(key: String): String? = null
                override fun write(key: String, markdown: String) = Unit
            },
            metadataStore = store,
            clientIdProvider = object : ClientIdProvider {
                override fun getOrCreateClientId(): String = "client"
            },
        ),
        scope = this,
        nowMillis = { currentTime },
    )

    private fun progress(
        value: String,
        type: PositionType = PositionType.Line,
    ) = ProgressUpdate(
        documentId = "doc",
        version = 1,
        positionType = type,
        positionValue = value,
        progressRatio = 0.5,
    )
}

private class CapturingApiClient : ApiClient {
    val updates = mutableListOf<ProgressUpdate>()

    override suspend fun listDocuments(clientId: String): List<DocumentSummary> = emptyList()

    override suspend fun getDocument(documentId: String, clientId: String): DocumentDetail =
        DocumentDetail(
            documentId,
            "",
            1,
            "",
            "",
            Instant.parse("2026-06-29T01:00:00Z"),
            CacheControl(0, 0),
            null,
        )

    override suspend fun updateProgress(clientId: String, update: ProgressUpdate): ProgressUpdateResult {
        updates += update
        return ProgressUpdateResult(update.documentId, update.version, Instant.parse("2026-06-29T00:00:00Z"))
    }
}

private class QueueStore : MetadataStore {
    private var queue = emptyList<ProgressUpdate>()

    override fun saveDocuments(documents: List<DocumentSummary>) = Unit
    override fun loadDocuments(): List<DocumentSummary> = emptyList()
    override fun saveDetail(detail: DocumentDetail) = Unit
    override fun loadDetail(documentId: String): DocumentDetail? = null
    override fun enqueueProgress(update: ProgressUpdate) {
        queue = queue + update
    }
    override fun loadProgressQueue(): List<ProgressUpdate> = queue
    override fun replaceProgressQueue(updates: List<ProgressUpdate>) {
        queue = updates
    }
}
