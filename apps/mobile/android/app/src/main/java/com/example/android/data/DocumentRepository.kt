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
            val result = apiClient.updateProgress(clientIdProvider.getOrCreateClientId(), update)
            cacheProgress(update, result.updatedAt)
            true
        } catch (_: Exception) {
            cacheProgress(update, updatedAt = null)
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

    private fun cacheProgress(update: ProgressUpdate, updatedAt: java.time.Instant?) {
        metadataStore.loadDetail(update.documentId)
            ?.takeIf { it.version == update.version }
            ?.let { detail ->
                metadataStore.saveDetail(
                    detail.copy(
                        readingProgress = ReadingProgress(
                            positionType = update.positionType,
                            positionValue = update.positionValue,
                            progressRatio = update.progressRatio,
                            updatedAt = updatedAt,
                        ),
                    ),
                )
            }
        val documents = metadataStore.loadDocuments()
        if (documents.any { it.documentId == update.documentId && it.version == update.version }) {
            metadataStore.saveDocuments(
                documents.map { document ->
                    if (document.documentId == update.documentId && document.version == update.version) {
                        document.copy(progressRatio = update.progressRatio, updatedAt = updatedAt)
                    } else {
                        document
                    }
                },
            )
        }
    }
}
