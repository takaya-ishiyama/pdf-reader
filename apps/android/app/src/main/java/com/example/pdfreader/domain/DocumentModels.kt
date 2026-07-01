package com.example.pdfreader.domain

data class DocumentId(val value: String) {
    init {
        require(value.isNotBlank()) { "documentId must not be blank" }
    }
}

data class ClientId(val value: String) {
    init {
        require(value.isNotBlank()) { "clientId must not be blank" }
    }
}

data class DocumentVersion(val value: Int) {
    init {
        require(value >= 1) { "version must be greater than or equal to 1" }
    }
}

data class ContentHash(val value: String) {
    init {
        require(value.isNotBlank()) { "contentHash must not be blank" }
    }
}

enum class PositionType(val wireName: String) {
    HeadingAnchor("heading_anchor"),
    Line("line"),
    Offset("offset");

    companion object {
        fun fromWireName(value: String): PositionType =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("invalid position type: $value")
    }
}

data class ReadingProgress(
    val documentId: DocumentId,
    val version: DocumentVersion,
    val positionType: PositionType,
    val positionValue: String,
    val progressRatio: Double,
) {
    init {
        require(positionValue.isNotBlank()) { "positionValue must not be blank" }
        require(progressRatio in 0.0..1.0) { "progressRatio must be between 0.0 and 1.0" }
    }
}

data class DocumentDetail(
    val documentId: DocumentId,
    val title: String,
    val version: DocumentVersion,
    val contentHash: ContentHash,
    val signedUrl: String,
    val readingProgress: ReadingProgress?,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(signedUrl.isNotBlank()) { "signedUrl must not be blank" }
    }
}
