package com.example.android.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class SharedPreferencesMetadataStore(
    private val preferences: SharedPreferences,
) : MetadataStore {
    override fun saveDocuments(documents: List<DocumentSummary>) {
        preferences.edit().putString(KEY_DOCUMENTS, JSONArray(documents.map { it.toJson() }).toString()).apply()
    }

    override fun loadDocuments(): List<DocumentSummary> =
        preferences.getString(KEY_DOCUMENTS, null)
            ?.let { JSONArray(it).mapJsonObjects { json -> json.toSummary() } }
            ?: emptyList()

    override fun saveDetail(detail: DocumentDetail) {
        preferences.edit().putString(detailKey(detail.documentId), detail.toJson().toString()).apply()
    }

    override fun loadDetail(documentId: String): DocumentDetail? =
        preferences.getString(detailKey(documentId), null)?.let { JSONObject(it).toDetail() }

    override fun enqueueProgress(update: ProgressUpdate) {
        replaceProgressQueue(loadProgressQueue() + update)
    }

    override fun loadProgressQueue(): List<ProgressUpdate> =
        preferences.getString(KEY_PROGRESS_QUEUE, null)
            ?.let { JSONArray(it).mapJsonObjects { json -> json.toProgressUpdate() } }
            ?: emptyList()

    override fun replaceProgressQueue(updates: List<ProgressUpdate>) {
        preferences.edit().putString(KEY_PROGRESS_QUEUE, JSONArray(updates.map { it.toJson() }).toString()).apply()
    }

    private fun DocumentSummary.toJson(): JSONObject = JSONObject()
        .put("document_id", documentId)
        .put("title", title)
        .put("version", version)
        .put("progress_ratio", progressRatio)
        .put("updated_at", updatedAt?.toString())

    private fun JSONObject.toSummary(): DocumentSummary = DocumentSummary(
        documentId = getString("document_id"),
        title = getString("title"),
        version = getInt("version"),
        progressRatio = getDouble("progress_ratio"),
        updatedAt = optNullableInstant("updated_at"),
    )

    private fun DocumentDetail.toJson(): JSONObject = JSONObject()
        .put("document_id", documentId)
        .put("title", title)
        .put("version", version)
        .put("content_hash", contentHash)
        .put("signed_url", signedUrl)
        .put("signed_url_expires_at", signedUrlExpiresAt.toString())
        .put(
            "cache_control",
            JSONObject()
                .put("max_age_seconds", cacheControl.maxAgeSeconds)
                .put("stale_while_revalidate_seconds", cacheControl.staleWhileRevalidateSeconds),
        )
        .put("reading_progress", readingProgress?.toJson())

    private fun JSONObject.toDetail(): DocumentDetail {
        val cacheControl = getJSONObject("cache_control")
        return DocumentDetail(
            documentId = getString("document_id"),
            title = getString("title"),
            version = getInt("version"),
            contentHash = getString("content_hash"),
            signedUrl = getString("signed_url"),
            signedUrlExpiresAt = Instant.parse(getString("signed_url_expires_at")),
            cacheControl = CacheControl(
                maxAgeSeconds = cacheControl.getInt("max_age_seconds"),
                staleWhileRevalidateSeconds = cacheControl.getInt("stale_while_revalidate_seconds"),
            ),
            readingProgress = optJSONObject("reading_progress")?.toProgress(),
        )
    }

    private fun ReadingProgress.toJson(): JSONObject = JSONObject()
        .put("position_type", positionType.wireValue)
        .put("position_value", positionValue)
        .put("progress_ratio", progressRatio)
        .put("updated_at", updatedAt?.toString())

    private fun JSONObject.toProgress(): ReadingProgress = ReadingProgress(
        positionType = PositionType.fromWireValue(getString("position_type")),
        positionValue = getString("position_value"),
        progressRatio = getDouble("progress_ratio"),
        updatedAt = optNullableInstant("updated_at"),
    )

    private fun ProgressUpdate.toJson(): JSONObject = JSONObject()
        .put("document_id", documentId)
        .put("version", version)
        .put("position_type", positionType.wireValue)
        .put("position_value", positionValue)
        .put("progress_ratio", progressRatio)

    private fun JSONObject.toProgressUpdate(): ProgressUpdate = ProgressUpdate(
        documentId = getString("document_id"),
        version = getInt("version"),
        positionType = PositionType.fromWireValue(getString("position_type")),
        positionValue = getString("position_value"),
        progressRatio = getDouble("progress_ratio"),
    )

    private fun <T> JSONArray.mapJsonObjects(transform: (JSONObject) -> T): List<T> =
        List(length()) { index -> transform(getJSONObject(index)) }

    private fun JSONObject.optNullableInstant(name: String): Instant? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }?.let(Instant::parse)
    }

    private fun detailKey(documentId: String): String = "document_detail_$documentId"

    private companion object {
        const val KEY_DOCUMENTS = "documents"
        const val KEY_PROGRESS_QUEUE = "progress_queue"
    }
}
