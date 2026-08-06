package com.example.android.data

interface ApiClient {
    suspend fun listDocuments(clientId: String): List<DocumentSummary>
    suspend fun getDocument(documentId: String, clientId: String): DocumentDetail
    suspend fun updateProgress(clientId: String, update: ProgressUpdate): ProgressUpdateResult
}
