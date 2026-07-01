package com.example.pdfreader.api

data class DocumentSummaryDto(
    val documentId: String,
    val title: String,
    val version: Int,
    val progressRatio: Double?,
    val updatedAt: String?,
)

data class DocumentDetailDto(
    val documentId: String,
    val title: String,
    val version: Int,
    val contentHash: String,
    val signedUrl: String,
    val signedUrlExpiresAt: String,
    val readingProgress: ReadingProgressDto?,
    val cacheControl: CacheControlDto = CacheControlDto(
        maxAgeSeconds = 86_400,
        staleWhileRevalidateSeconds = 604_800,
    ),
)

data class CacheControlDto(
    val maxAgeSeconds: Long,
    val staleWhileRevalidateSeconds: Long,
)

data class ReadingProgressDto(
    val positionType: String,
    val positionValue: String,
    val progressRatio: Double,
    val updatedAt: String? = null,
)

data class UpdateReadingProgressRequest(
    val clientId: String,
    val version: Int,
    val positionType: String,
    val positionValue: String,
    val progressRatio: Double,
)

interface DocumentApiClient {
    fun listDocuments(clientId: String): List<DocumentSummaryDto>
    fun getDocument(documentId: String, clientId: String): DocumentDetailDto
    fun updateReadingProgress(documentId: String, request: UpdateReadingProgressRequest)
}
