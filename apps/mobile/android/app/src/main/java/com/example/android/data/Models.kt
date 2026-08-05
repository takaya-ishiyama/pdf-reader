package com.example.android.data

import java.time.Instant

data class DocumentSummary(
    val documentId: String,
    val title: String,
    val version: Int,
    val progressRatio: Double,
    val updatedAt: Instant?,
)

data class ReadingProgress(
    val positionType: PositionType,
    val positionValue: String,
    val progressRatio: Double,
    val updatedAt: Instant? = null,
)

data class DocumentDetail(
    val documentId: String,
    val title: String,
    val version: Int,
    val contentHash: String,
    val signedUrl: String,
    val signedUrlExpiresAt: Instant,
    val cacheControl: CacheControl,
    val readingProgress: ReadingProgress?,
)

data class CacheControl(
    val maxAgeSeconds: Int,
    val staleWhileRevalidateSeconds: Int,
)

data class MarkdownDocument(
    val detail: DocumentDetail,
    val markdown: String,
    val fromCache: Boolean,
)

data class ProgressUpdate(
    val documentId: String,
    val version: Int,
    val positionType: PositionType,
    val positionValue: String,
    val progressRatio: Double,
) {
    init {
        require(documentId.isNotBlank()) { "documentId must not be blank" }
        require(version >= 1) { "version must be at least 1" }
        require(positionValue.isNotBlank()) { "positionValue must not be blank" }
        require(progressRatio in 0.0..1.0) { "progressRatio must be between 0.0 and 1.0" }
    }
}

enum class PositionType(val wireValue: String) {
    HeadingAnchor("heading_anchor"),
    Line("line"),
    Offset("offset");

    companion object {
        fun fromWireValue(value: String): PositionType = entries.firstOrNull { it.wireValue == value }
            ?: error("Unsupported position_type: $value")
    }
}

data class ProgressUpdateResult(
    val documentId: String,
    val version: Int,
    val updatedAt: Instant,
)

sealed class ResultState<out T> {
    data object Loading : ResultState<Nothing>()
    data class Loaded<T>(val value: T) : ResultState<T>()
    data class Failed(val message: String) : ResultState<Nothing>()
}
