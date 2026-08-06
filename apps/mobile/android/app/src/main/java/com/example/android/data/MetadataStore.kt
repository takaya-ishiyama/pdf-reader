package com.example.android.data

interface MetadataStore {
    fun saveDocuments(documents: List<DocumentSummary>)
    fun loadDocuments(): List<DocumentSummary>
    fun saveDetail(detail: DocumentDetail)
    fun loadDetail(documentId: String): DocumentDetail?
    fun enqueueProgress(update: ProgressUpdate)
    fun loadProgressQueue(): List<ProgressUpdate>
    fun replaceProgressQueue(updates: List<ProgressUpdate>)
}
