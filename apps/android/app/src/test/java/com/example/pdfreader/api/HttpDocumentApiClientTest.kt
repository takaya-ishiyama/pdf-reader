package com.example.pdfreader.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpDocumentApiClientTest {
    @Test
    fun listDocumentsMapsApiResponse() {
        val transport = FakeHttpTransport()
        transport.getResponses["/v1/documents?client_id=client-1"] = HttpResponse(
            statusCode = 200,
            body = """
                {
                  "documents": [
                    {
                      "document_id": "doc-1",
                      "title": "Sample",
                      "version": 3,
                      "progress_ratio": 0.42,
                      "updated_at": "2026-06-29T00:00:00Z"
                    }
                  ]
                }
            """.trimIndent(),
        )

        val documents = HttpDocumentApiClient(transport).listDocuments("client-1")

        assertEquals(1, documents.size)
        assertEquals("doc-1", documents.single().documentId)
        assertEquals("Sample", documents.single().title)
        assertEquals(3, documents.single().version)
        assertEquals(0.42, documents.single().progressRatio ?: -1.0, 0.0)
    }

    @Test
    fun getDocumentMapsSignedUrlAndReadingProgress() {
        val transport = FakeHttpTransport()
        transport.getResponses["/v1/documents/doc-1?client_id=client-1"] = HttpResponse(
            statusCode = 200,
            body = """
                {
                  "document_id": "doc-1",
                  "title": "Sample",
                  "version": 3,
                  "content_hash": "sha256:abc",
                  "signed_url": "https://signed.example/doc.md",
                  "signed_url_expires_at": "2026-06-29T01:00:00Z",
                  "cache_control": {
                    "max_age_seconds": 86400,
                    "stale_while_revalidate_seconds": 604800
                  },
                  "reading_progress": {
                    "position_type": "heading_anchor",
                    "position_value": "chapter-2",
                    "progress_ratio": 0.42,
                    "updated_at": "2026-06-29T00:00:00Z"
                  }
                }
            """.trimIndent(),
        )

        val document = HttpDocumentApiClient(transport).getDocument("doc-1", "client-1")

        assertEquals("sha256:abc", document.contentHash)
        assertEquals("https://signed.example/doc.md", document.signedUrl)
        assertEquals(86_400L, document.cacheControl.maxAgeSeconds)
        assertEquals(604_800L, document.cacheControl.staleWhileRevalidateSeconds)
        assertEquals("heading_anchor", document.readingProgress?.positionType)
        assertEquals("chapter-2", document.readingProgress?.positionValue)
    }

    @Test
    fun updateProgressSendsExpectedJsonBody() {
        val transport = FakeHttpTransport()
        transport.putResponses["/v1/documents/doc-1/progress"] = HttpResponse(200, """{"ok":true}""")

        HttpDocumentApiClient(transport).updateReadingProgress(
            documentId = "doc-1",
            request = UpdateReadingProgressRequest(
                clientId = "client-1",
                version = 3,
                positionType = "heading_anchor",
                positionValue = "chapter-2",
                progressRatio = 0.42,
            ),
        )

        assertEquals("/v1/documents/doc-1/progress", transport.lastPutPath)
        assertTrue(transport.lastPutBody.contains("\"client_id\":\"client-1\""))
        assertTrue(transport.lastPutBody.contains("\"version\":3"))
        assertTrue(transport.lastPutBody.contains("\"position_value\":\"chapter-2\""))
    }

    @Test(expected = ApiException::class)
    fun throwsOnNonSuccessfulResponse() {
        val transport = FakeHttpTransport()
        transport.getResponses["/v1/documents?client_id=client-1"] = HttpResponse(500, "error")

        HttpDocumentApiClient(transport).listDocuments("client-1")
    }
}

private class FakeHttpTransport : HttpTransport {
    val getResponses = mutableMapOf<String, HttpResponse>()
    val putResponses = mutableMapOf<String, HttpResponse>()
    var lastPutPath: String = ""
    var lastPutBody: String = ""

    override fun get(pathAndQuery: String): HttpResponse =
        getResponses.getValue(pathAndQuery)

    override fun putJson(path: String, body: String): HttpResponse {
        lastPutPath = path
        lastPutBody = body
        return putResponses.getValue(path)
    }
}
