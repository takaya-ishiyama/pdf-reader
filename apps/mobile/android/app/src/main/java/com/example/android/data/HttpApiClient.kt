package com.example.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant

class HttpApiClient(private val baseUrl: String) : ApiClient {
    override suspend fun listDocuments(clientId: String): List<DocumentSummary> = withContext(Dispatchers.IO) {
        val json = request("GET", "/v1/documents?client_id=${clientId.urlEncode()}")
        json.getJSONArray("documents").mapObjects { parseSummary(it) }
    }

    override suspend fun getDocument(documentId: String, clientId: String): DocumentDetail = withContext(Dispatchers.IO) {
        parseDetail(request("GET", "/v1/documents/${documentId.urlEncode()}?client_id=${clientId.urlEncode()}"))
    }

    override suspend fun updateProgress(clientId: String, update: ProgressUpdate): ProgressUpdateResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("client_id", clientId)
                .put("version", update.version)
                .put("position_type", update.positionType.wireValue)
                .put("position_value", update.positionValue)
                .put("progress_ratio", update.progressRatio)
            val json = request("PUT", "/v1/documents/${update.documentId.urlEncode()}/progress", body)
            ProgressUpdateResult(
                documentId = json.getString("document_id"),
                version = json.getInt("version"),
                updatedAt = Instant.parse(json.getString("updated_at")),
            )
        }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        }
        try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val response = stream.bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) {
                error("API request failed with HTTP ${connection.responseCode}: $response")
            }
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSummary(json: JSONObject): DocumentSummary = DocumentSummary(
        documentId = json.getString("document_id"),
        title = json.getString("title"),
        version = json.getInt("version"),
        progressRatio = json.optDouble("progress_ratio", 0.0),
        updatedAt = json.optString("updated_at").takeIf { it.isNotBlank() }?.let(Instant::parse),
    )

    private fun parseDetail(json: JSONObject): DocumentDetail {
        val progress = json.optJSONObject("reading_progress")?.let {
            ReadingProgress(
                positionType = PositionType.fromWireValue(it.getString("position_type")),
                positionValue = it.getString("position_value"),
                progressRatio = it.getDouble("progress_ratio"),
                updatedAt = it.optString("updated_at").takeIf { value -> value.isNotBlank() }?.let(Instant::parse),
            )
        }
        val cacheControl = json.getJSONObject("cache_control")
        return DocumentDetail(
            documentId = json.getString("document_id"),
            title = json.getString("title"),
            version = json.getInt("version"),
            contentHash = json.getString("content_hash"),
            signedUrl = json.getString("signed_url"),
            signedUrlExpiresAt = Instant.parse(json.getString("signed_url_expires_at")),
            cacheControl = CacheControl(
                maxAgeSeconds = cacheControl.getInt("max_age_seconds"),
                staleWhileRevalidateSeconds = cacheControl.getInt("stale_while_revalidate_seconds"),
            ),
            readingProgress = progress,
        )
    }

    private fun JSONArray.mapObjects(transform: (JSONObject) -> DocumentSummary): List<DocumentSummary> =
        List(length()) { index -> transform(getJSONObject(index)) }

    private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
}
