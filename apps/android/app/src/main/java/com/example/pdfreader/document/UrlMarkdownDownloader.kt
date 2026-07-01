package com.example.pdfreader.document

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class UrlMarkdownDownloader : MarkdownDownloader {
    override fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        val statusCode = connection.responseCode
        val body = if (statusCode in 200..299) {
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        }
        connection.disconnect()

        if (statusCode !in 200..299) {
            throw MarkdownDownloadException(statusCode, body)
        }

        return body
    }
}

class MarkdownDownloadException(
    val statusCode: Int,
    body: String,
) : RuntimeException("Markdown download failed with status $statusCode: $body")
