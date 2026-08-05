package com.example.android.sync

import com.example.android.data.DocumentRepository
import com.example.android.data.PositionType
import com.example.android.data.ProgressUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ProgressSyncManager(
    private val repository: DocumentRepository,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 3_000,
    private val periodicMillis: Long = 30_000,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private var pending: ProgressUpdate? = null
    private var debounceJob: Job? = null
    private var lastSentAtMillis: Long = 0

    fun onPositionChanged(update: ProgressUpdate) {
        val previous = pending
        pending = update
        val crossedHeading = previous != null &&
            update.positionType == PositionType.HeadingAnchor &&
            previous.positionValue != update.positionValue

        if (crossedHeading || nowMillis() - lastSentAtMillis >= periodicMillis) {
            sendNow()
            return
        }

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMillis)
            sendNow()
        }
    }

    fun flush() {
        sendNow()
        scope.launch { repository.flushQueuedProgress() }
    }

    fun onBackgrounded() {
        flush()
    }

    private fun sendNow() {
        val update = pending ?: return
        pending = null
        debounceJob?.cancel()
        debounceJob = null
        scope.launch {
            repository.updateProgress(update)
            lastSentAtMillis = nowMillis()
        }
    }
}
