package com.example.android.ui

import com.example.android.data.DocumentRepository
import com.example.android.data.DocumentSummary
import com.example.android.data.MarkdownDocument
import com.example.android.data.ResultState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DocumentReaderViewModel(
    private val repository: DocumentRepository,
    private val scope: CoroutineScope,
) {
    private val mutableDocuments = MutableStateFlow<ResultState<List<DocumentSummary>>>(ResultState.Loading)
    val documents: StateFlow<ResultState<List<DocumentSummary>>> = mutableDocuments

    private val mutableDocument = MutableStateFlow<ResultState<MarkdownDocument>?>(null)
    val document: StateFlow<ResultState<MarkdownDocument>?> = mutableDocument

    fun loadDocuments() {
        mutableDocuments.value = ResultState.Loading
        scope.launch {
            try {
                mutableDocuments.value = ResultState.Loaded(repository.listDocuments())
            } catch (error: Exception) {
                mutableDocuments.value = ResultState.Failed(error.message ?: "Failed to load documents")
            }
        }
    }

    fun openDocument(documentId: String) {
        mutableDocument.value = ResultState.Loading
        scope.launch {
            try {
                mutableDocument.value = ResultState.Loaded(repository.loadDocument(documentId))
            } catch (error: Exception) {
                mutableDocument.value = ResultState.Failed(error.message ?: "Failed to load document")
            }
        }
    }
}
