package com.example.pdfreader

import android.content.Context
import com.example.pdfreader.api.HttpDocumentApiClient
import com.example.pdfreader.api.UrlConnectionHttpTransport
import com.example.pdfreader.cache.FileMarkdownStorage
import com.example.pdfreader.cache.SharedPreferencesDocumentMetadataStorage
import com.example.pdfreader.document.ApiProgressAdapter
import com.example.pdfreader.document.DocumentRepository
import com.example.pdfreader.document.MarkdownViewerViewModel
import com.example.pdfreader.document.UrlMarkdownDownloader
import com.example.pdfreader.domain.ClientId
import com.example.pdfreader.progress.ProgressSyncManager
import com.example.pdfreader.progress.SharedPreferencesProgressOfflineQueue
import com.example.pdfreader.progress.SystemClock
import com.example.pdfreader.tts.AndroidTextToSpeechEngine
import com.example.pdfreader.tts.TextToSpeechController

class AppFactory(
    private val context: Context,
) {
    private val clientIdProvider = ClientIdProvider(context)

    fun clientId(): ClientId = ClientId(clientIdProvider.getOrCreate())

    fun markdownViewerViewModel(): MarkdownViewerViewModel {
        val api = HttpDocumentApiClient(UrlConnectionHttpTransport(BuildConfig.API_BASE_URL))
        val progressSyncManager = ProgressSyncManager(
            api = ApiProgressAdapter(api, clientId()),
            clock = SystemClock,
            offlineQueue = SharedPreferencesProgressOfflineQueue(context),
        )
        val repository = DocumentRepository(
            api = api,
            markdownDownloader = UrlMarkdownDownloader(),
            markdownStorage = FileMarkdownStorage(context.filesDir.resolve("markdown-cache")),
            progressSyncManager = progressSyncManager,
            metadataStorage = SharedPreferencesDocumentMetadataStorage(context),
        )
        return MarkdownViewerViewModel(repository)
    }

    fun textToSpeechController(): TextToSpeechController =
        TextToSpeechController(AndroidTextToSpeechEngine(context.applicationContext))
}
