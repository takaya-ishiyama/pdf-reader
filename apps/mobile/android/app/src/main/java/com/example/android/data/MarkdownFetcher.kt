package com.example.android.data

interface MarkdownFetcher {
    suspend fun fetch(url: String): String
}
