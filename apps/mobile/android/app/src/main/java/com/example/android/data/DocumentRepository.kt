package com.example.android.data

class DocumentRepository(
    private val apiClient: ApiClient,
    private val markdownFetcher: MarkdownFetcher,
    private val markdownCache: MarkdownCache,
    private val metadataStore: MetadataStore,
    private val clientIdProvider: ClientIdProvider,
) {
    suspend fun listDocuments(): List<DocumentSummary> {
        val cached = metadataStore.loadDocuments()
        return try {
            apiClient.listDocuments(clientIdProvider.getOrCreateClientId()).also(metadataStore::saveDocuments)
        } catch (_: Exception) {
            cached
        }
    }

    suspend fun loadDocument(documentId: String): MarkdownDocument {
        val clientId = clientIdProvider.getOrCreateClientId()
        val cachedDetail = metadataStore.loadDetail(documentId)
        val cachedMarkdown = cachedDetail?.let { markdownCache.read(cacheKey(it)) }
        val detail = try {
            apiClient.getDocument(documentId, clientId).also(metadataStore::saveDetail)
        } catch (error: Exception) {
            if (cachedDetail != null && cachedMarkdown != null) {
                return MarkdownDocument(cachedDetail, cachedMarkdown, fromCache = true)
            }
            throw error
        }

        val key = cacheKey(detail)
        val cachedFreshMarkdown = markdownCache.read(key)
        if (cachedFreshMarkdown != null) {
            return MarkdownDocument(detail, cachedFreshMarkdown, fromCache = true)
        }

        val markdown = markdownFetcher.fetch(detail.signedUrl)
        markdownCache.write(key, markdown)
        return MarkdownDocument(detail, markdown, fromCache = false)
    }

    suspend fun updateProgress(update: ProgressUpdate): Boolean {
        return try {
            apiClient.updateProgress(clientIdProvider.getOrCreateClientId(), update)
            true
        } catch (_: Exception) {
            metadataStore.enqueueProgress(update)
            false
        }
    }

    suspend fun flushQueuedProgress(): Int {
        val queued = metadataStore.loadProgressQueue()
        val remaining = mutableListOf<ProgressUpdate>()
        var sent = 0
        for (update in queued) {
            try {
                apiClient.updateProgress(clientIdProvider.getOrCreateClientId(), update)
                sent += 1
            } catch (_: Exception) {
                remaining += update
            }
        }
        metadataStore.replaceProgressQueue(remaining)
        return sent
    }

    fun cacheKey(detail: DocumentDetail): String =
        markdownCache.key(detail.documentId, detail.version, detail.contentHash)
}
