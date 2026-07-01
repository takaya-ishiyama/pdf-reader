package com.example.pdfreader.progress

import com.example.pdfreader.domain.ReadingProgress
import com.example.pdfreader.domain.PositionType
import java.io.IOException

interface ProgressApi {
    fun update(progress: ReadingProgress)
}

interface Clock {
    fun nowMillis(): Long
}

class ProgressSyncManager(
    private val api: ProgressApi,
    private val clock: Clock,
    private val debounceMillis: Long = 3_000,
    private val periodicSyncMillis: Long = 30_000,
    private val offlineQueue: ProgressOfflineQueue = InMemoryProgressOfflineQueue(),
    private val initialRetryDelayMillis: Long = 5_000,
    private val maxRetryDelayMillis: Long = 60_000,
) {
    private var pending: ReadingProgress? = null
    private var pendingSinceMillis: Long? = null
    private var lastSyncedAtMillis: Long? = null
    private var lastSyncedHeadingAnchor: String? = null
    private var lastRetryFailureAtMillis: Long? = null
    private var currentRetryDelayMillis: Long = initialRetryDelayMillis

    var isOnline: Boolean = true

    fun onProgressChanged(progress: ReadingProgress) {
        if (shouldSyncImmediately(progress)) {
            sendOrQueue(progress)
            pending = null
            pendingSinceMillis = null
            return
        }
        pending = progress
        pendingSinceMillis = clock.nowMillis()
    }

    fun tick() {
        val now = clock.nowMillis()
        retryOfflineQueueIfDue(now)
        val progress = pending ?: return
        val pendingSince = pendingSinceMillis ?: return
        val dueToDebounce = now - pendingSince >= debounceMillis
        val dueToPeriodicSync = lastSyncedAtMillis?.let { now - it >= periodicSyncMillis } ?: true

        if (dueToDebounce && dueToPeriodicSync) {
            sendOrQueue(progress)
            pending = null
            pendingSinceMillis = null
        }
    }

    fun onBackgrounded() {
        pending?.let {
            sendOrQueue(it)
            pending = null
            pendingSinceMillis = null
        }
    }

    fun flushOfflineQueue() {
        if (!isOnline) return
        val queued = offlineQueue.drain()
        queued.forEachIndexed { index, progress ->
            try {
                val sent = sendOrQueue(progress)
                if (!sent) {
                    queued.drop(index + 1).forEach { offlineQueue.enqueue(it) }
                    return
                }
            } catch (exception: Exception) {
                queued.drop(index + 1).forEach { offlineQueue.enqueue(it) }
                throw exception
            }
        }
        if (offlineQueue.size() == 0) {
            resetRetryState()
        }
    }

    fun queuedCount(): Int = offlineQueue.size()

    private fun sendOrQueue(progress: ReadingProgress): Boolean {
        if (!isOnline) {
            offlineQueue.enqueue(progress)
            return false
        }

        return try {
            api.update(progress)
            lastSyncedAtMillis = clock.nowMillis()
            if (progress.positionType == PositionType.HeadingAnchor) {
                lastSyncedHeadingAnchor = progress.positionValue
            }
            true
        } catch (exception: IOException) {
            offlineQueue.enqueue(progress)
            recordRetryFailure()
            false
        }
    }

    private fun retryOfflineQueueIfDue(now: Long) {
        if (!isOnline || offlineQueue.size() == 0) return
        val lastFailure = lastRetryFailureAtMillis
        val due = lastFailure == null || now - lastFailure >= currentRetryDelayMillis
        if (due) flushOfflineQueue()
    }

    private fun recordRetryFailure() {
        if (lastRetryFailureAtMillis != null) {
            currentRetryDelayMillis = (currentRetryDelayMillis * 2).coerceAtMost(maxRetryDelayMillis)
        }
        lastRetryFailureAtMillis = clock.nowMillis()
    }

    private fun resetRetryState() {
        lastRetryFailureAtMillis = null
        currentRetryDelayMillis = initialRetryDelayMillis
    }

    private fun shouldSyncImmediately(progress: ReadingProgress): Boolean =
        progress.positionType == PositionType.HeadingAnchor &&
            lastSyncedHeadingAnchor != null &&
            lastSyncedHeadingAnchor != progress.positionValue
}

interface ProgressOfflineQueue {
    fun enqueue(progress: ReadingProgress)
    fun drain(): List<ReadingProgress>
    fun size(): Int
}

class InMemoryProgressOfflineQueue : ProgressOfflineQueue {
    private val queue = mutableListOf<ReadingProgress>()

    override fun enqueue(progress: ReadingProgress) {
        queue += progress
    }

    override fun drain(): List<ReadingProgress> {
        val queued = queue.toList()
        queue.clear()
        return queued
    }

    override fun size(): Int = queue.size
}
