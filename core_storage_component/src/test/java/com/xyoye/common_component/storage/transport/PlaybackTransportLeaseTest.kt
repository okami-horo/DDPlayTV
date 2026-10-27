package com.xyoye.common_component.storage.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PlaybackTransportLeaseTest {
    @Test
    fun releaseIsIdempotentAcrossConcurrentCallers() {
        val releases = AtomicInteger()
        val lease =
            ManagedPlaybackTransportLease(
                "https://example.test/video",
                PlaybackTransportType.DIRECT,
                listOf({
                    releases.incrementAndGet()
                    Unit
                })
            )
        assertFalse(lease.isReleased)
        val start = CountDownLatch(1)
        val callers =
            (1..8).map {
                thread {
                    start.await()
                    lease.release()
                }
            }
        start.countDown()
        callers.forEach { it.join() }
        assertTrue(lease.isReleased)
        assertEquals(1, releases.get())
    }

    @Test
    fun failedCleanupDoesNotSkipLaterResourcesOrReleaseAnotherLease() {
        val closed = mutableListOf<String>()
        val first =
            ManagedPlaybackTransportLease(
                "http://127.0.0.1:12000/first",
                PlaybackTransportType.SMB,
                listOf(
                    {
                        closed.add("server")
                        throw IllegalStateException("secret")
                    },
                    {
                        closed.add("file")
                        Unit
                    },
                    {
                        closed.add("storage")
                        Unit
                    }
                )
            )
        val second =
            ManagedPlaybackTransportLease(
                "http://127.0.0.1:12001/second",
                PlaybackTransportType.SMB,
                listOf({
                    closed.add("other")
                    Unit
                })
            )
        first.release()
        first.release()
        assertEquals(listOf("server", "file", "storage"), closed)
        assertFalse(second.isReleased)
        assertNotEquals(first.leaseId, second.leaseId)
        assertFalse(first.toString().contains("127.0.0.1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyPlaybackUrlIsRejected() {
        ManagedPlaybackTransportLease(" ", PlaybackTransportType.DIRECT, emptyList())
    }
}
