package com.example.pdfreader.document

import com.example.pdfreader.api.DocumentApiClient
import com.example.pdfreader.api.DocumentDetailDto
import com.example.pdfreader.api.DocumentSummaryDto
import com.example.pdfreader.api.ReadingProgressDto
import com.example.pdfreader.cache.CachedDocumentMetadata
import com.example.pdfreader.cache.DocumentMetadataStorage
import com.example.pdfreader.cache.InMemoryDocumentMetadataStorage
import com.example.pdfreader.cache.MarkdownCacheKey
import com.example.pdfreader.cache.MarkdownStorage
import com.example.pdfreader.domain.ClientId
import com.example.pdfreader.domain.ContentHash
import com.example.pdfreader.domain.DocumentDetail
import com.example.pdfreader.domain.DocumentId
import com.example.pdfreader.domain.DocumentVersion
import com.example.pdfreader.domain.PositionType
import com.example.pdfreader.domain.ReadingProgress
import com.example.pdfreader.progress.ProgressSyncManager

interface MarkdownDownloader {
    fun download(url: String): String
}

data class LoadedDocument(
    val detail: DocumentDetail,
    val markdown: String,
    val cacheKey: MarkdownCacheKey,
)

data class DocumentListItem(
    val documentId: DocumentId,
    val title: String,
    val version: DocumentVersion,
    val progressRatio: Double?,
    val updatedAt: String?,
)

class DocumentRepository(
    private val api: DocumentApiClient,
    private val markdownDownloader: MarkdownDownloader,
    private val markdownStorage: MarkdownStorage,
    private val progressSyncManager: ProgressSyncManager,
    private val metadataStorage: DocumentMetadataStorage = InMemoryDocumentMetadataStorage(),
) {
    fun listDocuments(clientId: String): List<DocumentListItem> =
        api.listDocuments(clientId).map { it.toDomain() }

    fun loadDocument(documentId: String, clientId: String): LoadedDocument {
        val detailDto = try {
            api.getDocument(documentId, clientId)
        } catch (error: Exception) {
            return loadCachedDocument(documentId) ?: throw error
        }
        val cacheKey = cacheKeyFor(detailDto)
        val markdown = try {
            markdownStorage.read(cacheKey)
                ?: markdownDownloader.download(detailDto.signedUrl).also {
                    markdownStorage.write(cacheKey, it)
                }
        } catch (error: Exception) {
            markdownStorage.read(cacheKey)
                ?: return loadCachedDocument(documentId) ?: throw error
        }
        val detail = detailDto.toDomain()
        metadataStorage.write(detail.toCachedMetadata())

        return LoadedDocument(
            detail = detail,
            markdown = markdown,
            cacheKey = cacheKey,
        )
    }

    fun loadCachedDocument(documentId: String): LoadedDocument? {
        val metadata = metadataStorage.read(DocumentId(documentId)) ?: return null
        val cacheKey = MarkdownCacheKey(
            documentId = metadata.documentId,
            version = metadata.version,
            contentHash = metadata.contentHash,
        )
        val markdown = markdownStorage.read(cacheKey) ?: return null
        return LoadedDocument(
            detail = DocumentDetail(
                documentId = metadata.documentId,
                title = metadata.title,
                version = metadata.version,
                contentHash = metadata.contentHash,
                signedUrl = metadata.signedUrl,
                readingProgress = metadata.readingProgress,
            ),
            markdown = markdown,
            cacheKey = cacheKey,
        )
    }

    fun cacheDocument(
        documentId: String,
        version: Int,
        contentHash: String,
        markdown: String,
    ): MarkdownCacheKey {
        val key = MarkdownCacheKey(
            documentId = DocumentId(documentId),
            version = DocumentVersion(version),
            contentHash = ContentHash(contentHash),
        )
        markdownStorage.write(key, markdown)
        return key
    }

    fun onReadingProgressChanged(
        documentId: String,
        version: Int,
        positionType: String,
        positionValue: String,
        progressRatio: Double,
    ) {
        progressSyncManager.onProgressChanged(
            ReadingProgress(
                documentId = DocumentId(documentId),
                version = DocumentVersion(version),
                positionType = PositionType.fromWireName(positionType),
                positionValue = positionValue,
                progressRatio = progressRatio,
            ),
        )
    }

    fun tickProgressSync() {
        progressSyncManager.tick()
    }

    fun onBackgrounded() {
        progressSyncManager.onBackgrounded()
    }

    private fun cacheKeyFor(detail: DocumentDetailDto): MarkdownCacheKey =
        MarkdownCacheKey(
            documentId = DocumentId(detail.documentId),
            version = DocumentVersion(detail.version),
            contentHash = ContentHash(detail.contentHash),
        )

    private fun DocumentSummaryDto.toDomain(): DocumentListItem =
        DocumentListItem(
            documentId = DocumentId(documentId),
            title = title,
            version = DocumentVersion(version),
            progressRatio = progressRatio,
            updatedAt = updatedAt,
        )

    private fun DocumentDetailDto.toDomain(): DocumentDetail =
        DocumentDetail(
            documentId = DocumentId(documentId),
            title = title,
            version = DocumentVersion(version),
            contentHash = ContentHash(contentHash),
            signedUrl = signedUrl,
            readingProgress = readingProgress?.toDomain(documentId, version),
        )

    private fun ReadingProgressDto.toDomain(
        documentId: String,
        version: Int,
    ): ReadingProgress =
        ReadingProgress(
            documentId = DocumentId(documentId),
            version = DocumentVersion(version),
            positionType = PositionType.fromWireName(positionType),
            positionValue = positionValue,
            progressRatio = progressRatio,
        )

    private fun DocumentDetail.toCachedMetadata(): CachedDocumentMetadata =
        CachedDocumentMetadata(
            documentId = documentId,
            title = title,
            version = version,
            contentHash = contentHash,
            signedUrl = signedUrl,
            readingProgress = readingProgress,
        )
}

class ApiProgressAdapter(
    private val api: DocumentApiClient,
    private val clientId: ClientId,
) : com.example.pdfreader.progress.ProgressApi {
    override fun update(progress: ReadingProgress) {
        api.updateReadingProgress(
            documentId = progress.documentId.value,
            request = com.example.pdfreader.api.UpdateReadingProgressRequest(
                clientId = clientId.value,
                version = progress.version.value,
                positionType = progress.positionType.wireName,
                positionValue = progress.positionValue,
                progressRatio = progress.progressRatio,
            ),
        )
    }
}
