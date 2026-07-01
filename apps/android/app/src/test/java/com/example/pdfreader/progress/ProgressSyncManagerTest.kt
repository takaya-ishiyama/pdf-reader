package com.example.pdfreader.progress

import com.example.pdfreader.domain.DocumentId
import com.example.pdfreader.domain.DocumentVersion
import com.example.pdfreader.domain.PositionType
import com.example.pdfreader.domain.ReadingProgress
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ProgressSyncManagerTest {
    @Test
    fun doesNotSendBeforeDebounceWindow() {
        val api = FakeProgressApi()
        val clock = FakeClock()
        val manager = ProgressSyncManager(api, clock)

        manager.onProgressChanged(progress("chapter-1", 0.2))
        clock.advance(2_999)
        manager.tick()

        assertEquals(0, api.sent.size)
    }

    @Test
    fun sendsAfterDebounceWindow() {
        val api = FakeProgressApi()
        val clock = FakeClock()
        val manager = ProgressSyncManager(api, clock)

        manager.onProgressChanged(progress("chapter-1", 0.2))
        clock.advance(3_000)
        manager.tick()

        assertEquals(1, api.sent.size)
        assertEquals("chapter-1", api.sent.single().positionValue)
    }

    @Test
    fun sendsImmediatelyWhenHeadingAnchorChangesAfterPreviousSync() {
        val api = FakeProgressApi()
        val clock = FakeClock()
        val manager = ProgressSyncManager(api, clock)

        manager.onProgressChanged(progress("chapter-1", 0.2))
        clock.advance(3_000)
        manager.tick()

        manager.onProgressChanged(progress("chapter-2", 0.3))

        assertEquals(2, api.sent.size)
        assertEquals("chapter-2", api.sent.last().positionValue)
    }

    @Test
    fun doesNotSendImmediatelyWhenHeadingAnchorHasNotChanged() {
        val api = FakeProgressApi()
        val clock = FakeClock()
        val manager = ProgressSyncManager(api, clock)

        manager.onProgressChanged(progress("chapter-1", 0.2))
        clock.advance(3_000)
        manager.tick()

        manager.onProgressChanged(progress("chapter-1", 0.25))

        assertEquals(1, api.sent.size)
    }

    @Test
    fun backgroundFlushesPendingProgressImmediately() {
        val api = FakeProgressApi()
        val manager = ProgressSyncManager(api, FakeClock())

        manager.onProgressChanged(progress("chapter-2", 0.4))
        manager.onBackgrounded()

        assertEquals(1, api.sent.size)
        assertEquals("chapter-2", api.sent.single().positionValue)
    }

    @Test
    fun offlineProgressIsQueuedAndFlushedWhenOnline() {
        val api = FakeProgressApi()
        val clock = FakeClock()
        val manager = ProgressSyncManager(api, clock)
        manager.isOnline = false

        manager.onProgressChanged(progress("chapter-3", 0.6))
        clock.advance(3_000)
        manager.tick()

        assertEquals(0, api.sent.size)
        assertEquals(1, manager.queuedCount())

        manager.isOnline = true
        manager.flushOfflineQueue()

        assertEquals(1, api.sent.size)
        assertEquals(0, manager.queuedCount())
    }

    @Test
    fun offlineQueueCanBeSharedAcrossManagerInstances() {
        val offlineQueue = InMemoryProgressOfflineQueue()
        val clock = FakeClock()
        val firstApi = FakeProgressApi()
        val firstManager = ProgressSyncManager(
            api = firstApi,
            clock = clock,
            offlineQueue = offlineQueue,
        )
        firstManager.isOnline = false

        firstManager.onProgressChanged(progress("chapter-4", 0.8))
        clock.advance(3_000)
        firstManager.tick()

        val secondApi = FakeProgressApi()
        val secondManager = ProgressSyncManager(
            api = secondApi,
            clock = clock,
            offlineQueue = offlineQueue,
        )

        secondManager.flushOfflineQueue()

        assertEquals(0, firstApi.sent.size)
        assertEquals(1, secondApi.sent.size)
        assertEquals("chapter-4", secondApi.sent.single().positionValue)
        assertEquals(0, secondManager.queuedCount())
    }

    @Test
    fun failedOnlineSendIsKeptInOfflineQueue() {
        val api = FakeProgressApi(shouldFail = true)
        val clock = FakeClock()
        val manager = ProgressSyncManager(api, clock)

        manager.onProgressChanged(progress("chapter-5", 0.9))
        clock.advance(3_000)
        manager.tick()

        assertEquals(0, api.sent.size)
        assertEquals(1, manager.queuedCount())
    }

    @Test
    fun queuedProgressRetriesAfterBackoffDelay() {
        val api = FakeProgressApi(shouldFail = true)
        val clock = FakeClock()
        val manager = ProgressSyncManager(
            api = api,
            clock = clock,
            initialRetryDelayMillis = 5_000,
        )

        manager.onProgressChanged(progress("chapter-6", 0.5))
        clock.advance(3_000)
        manager.tick()

        assertEquals(1, manager.queuedCount())

        api.shouldFail = false
        clock.advance(4_999)
        manager.tick()
        assertEquals(0, api.sent.size)
        assertEquals(1, manager.queuedCount())

        clock.advance(1)
        manager.tick()
        assertEquals(1, api.sent.size)
        assertEquals("chapter-6", api.sent.single().positionValue)
        assertEquals(0, manager.queuedCount())
    }

    @Test
    fun retryStopsAfterNetworkFailureAndKeepsUnattemptedItemsQueued() {
        val offlineQueue = InMemoryProgressOfflineQueue()
        offlineQueue.enqueue(progress("chapter-7", 0.5))
        offlineQueue.enqueue(progress("chapter-8", 0.7))
        val api = FakeProgressApi(shouldFail = true)
        val manager = ProgressSyncManager(
            api = api,
            clock = FakeClock(),
            offlineQueue = offlineQueue,
        )

        manager.flushOfflineQueue()

        assertEquals(0, api.sent.size)
        assertEquals(2, manager.queuedCount())
    }

    @Test
    fun flushKeepsUnattemptedItemsWhenPermanentFailureStopsSync() {
        val offlineQueue = InMemoryProgressOfflineQueue()
        offlineQueue.enqueue(progress("chapter-9", 0.5))
        offlineQueue.enqueue(progress("chapter-10", 0.7))
        val api = FakeProgressApi(permanentFailure = IllegalStateException("conflict"))
        val manager = ProgressSyncManager(
            api = api,
            clock = FakeClock(),
            offlineQueue = offlineQueue,
        )

        try {
            manager.flushOfflineQueue()
            fail("expected permanent failure")
        } catch (exception: IllegalStateException) {
            assertEquals("conflict", exception.message)
        }

        assertEquals(0, api.sent.size)
        assertEquals(1, manager.queuedCount())
    }

    private fun progress(position: String, ratio: Double): ReadingProgress =
        ReadingProgress(
            documentId = DocumentId("doc-1"),
            version = DocumentVersion(1),
            positionType = PositionType.HeadingAnchor,
            positionValue = position,
            progressRatio = ratio,
        )
}

private class FakeProgressApi(
    var shouldFail: Boolean = false,
    private val permanentFailure: RuntimeException? = null,
) : ProgressApi {
    val sent = mutableListOf<ReadingProgress>()

    override fun update(progress: ReadingProgress) {
        permanentFailure?.let { throw it }
        if (shouldFail) throw IOException("network unavailable")
        sent += progress
    }
}

private class FakeClock : Clock {
    private var now = 0L

    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}
