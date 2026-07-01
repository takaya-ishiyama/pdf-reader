package com.example.pdfreader.api

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class HttpResponse(
    val statusCode: Int,
    val body: String,
)

interface HttpTransport {
    fun get(pathAndQuery: String): HttpResponse
    fun putJson(path: String, body: String): HttpResponse
}

class UrlConnectionHttpTransport(
    private val baseUrl: String,
) : HttpTransport {
    override fun get(pathAndQuery: String): HttpResponse =
        request(method = "GET", pathAndQuery = pathAndQuery, body = null)

    override fun putJson(path: String, body: String): HttpResponse =
        request(method = "PUT", pathAndQuery = path, body = body)

    private fun request(
        method: String,
        pathAndQuery: String,
        body: String?,
    ): HttpResponse {
        val connection = URL(baseUrl.trimEnd('/') + pathAndQuery).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/json")
            connection.outputStream.use { output -> output.writeUtf8(body) }
        }

        val statusCode = connection.responseCode
        val responseBody = if (statusCode in 200..299) {
            connection.inputStream.readUtf8()
        } else {
            connection.errorStream?.readUtf8().orEmpty()
        }
        connection.disconnect()

        return HttpResponse(statusCode, responseBody)
    }
}

class HttpDocumentApiClient(
    private val transport: HttpTransport,
) : DocumentApiClient {
    override fun listDocuments(clientId: String): List<DocumentSummaryDto> {
        val response = transport.get("/v1/documents?client_id=${urlEncode(clientId)}")
        ensureSuccess(response)
        return DocumentJsonCodec.decodeDocumentSummaries(response.body)
    }

    override fun getDocument(documentId: String, clientId: String): DocumentDetailDto {
        val response = transport.get("/v1/documents/${urlEncode(documentId)}?client_id=${urlEncode(clientId)}")
        ensureSuccess(response)
        return DocumentJsonCodec.decodeDocumentDetail(response.body)
    }

    override fun updateReadingProgress(
        documentId: String,
        request: UpdateReadingProgressRequest,
    ) {
        val response = transport.putJson(
            path = "/v1/documents/${urlEncode(documentId)}/progress",
            body = DocumentJsonCodec.encodeUpdateReadingProgressRequest(request),
        )
        ensureSuccess(response)
    }

    private fun ensureSuccess(response: HttpResponse) {
        if (response.statusCode !in 200..299) {
            throw ApiException(response.statusCode, response.body)
        }
    }
}

class ApiException(
    val statusCode: Int,
    body: String,
) : RuntimeException("API request failed with status $statusCode: $body")

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

private fun OutputStream.writeUtf8(value: String) {
    write(value.toByteArray(StandardCharsets.UTF_8))
}

private fun InputStream.readUtf8(): String =
    bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
