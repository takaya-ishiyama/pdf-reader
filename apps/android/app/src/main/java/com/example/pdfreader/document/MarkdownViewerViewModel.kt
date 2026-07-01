package com.example.pdfreader.document

import com.example.pdfreader.domain.ReadingProgress

sealed interface MarkdownViewerState {
    data object Idle : MarkdownViewerState
    data object Loading : MarkdownViewerState
    data class DocumentList(
        val documents: List<DocumentListItem>,
    ) : MarkdownViewerState
    data class Content(
        val title: String,
        val markdown: String,
        val version: Int,
        val readingProgress: ReadingProgress?,
    ) : MarkdownViewerState
    data class Error(val message: String) : MarkdownViewerState
}

class MarkdownViewerViewModel(
    private val repository: DocumentRepository,
) {
    var state: MarkdownViewerState = MarkdownViewerState.Idle
        private set

    fun loadDocuments(clientId: String) {
        state = MarkdownViewerState.Loading
        state = try {
            MarkdownViewerState.DocumentList(repository.listDocuments(clientId))
        } catch (error: Exception) {
            MarkdownViewerState.Error(error.message ?: "failed to load documents")
        }
    }

    fun load(documentId: String, clientId: String) {
        state = MarkdownViewerState.Loading
        state = try {
            val loaded = repository.loadDocument(documentId, clientId)
            loaded.toContentState()
        } catch (error: Exception) {
            MarkdownViewerState.Error(error.message ?: "failed to load document")
        }
    }

    fun loadCached(documentId: String): Boolean {
        val loaded = repository.loadCachedDocument(documentId) ?: return false
        state = loaded.toContentState()
        return true
    }

    fun onProgressChanged(
        documentId: String,
        version: Int,
        positionType: String,
        positionValue: String,
        progressRatio: Double,
    ) {
        repository.onReadingProgressChanged(
            documentId = documentId,
            version = version,
            positionType = positionType,
            positionValue = positionValue,
            progressRatio = progressRatio,
        )
    }

    fun tickProgressSync() {
        repository.tickProgressSync()
    }

    fun onBackgrounded() {
        repository.onBackgrounded()
    }

    private fun LoadedDocument.toContentState(): MarkdownViewerState.Content =
        MarkdownViewerState.Content(
            title = detail.title,
            markdown = markdown,
            version = detail.version.value,
            readingProgress = detail.readingProgress,
        )
}
