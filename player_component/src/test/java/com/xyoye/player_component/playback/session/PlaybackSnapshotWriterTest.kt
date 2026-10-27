package com.xyoye.player_component.playback.session

import com.xyoye.player_component.playback.handoff.PlaybackSnapshotTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackSnapshotWriterTest {
    @Test fun samplesEveryFiveSecondsAndEventSavesImmediatelyWithoutConsumingNewPending() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("active"))
            f.session.startSnapshotWriter(backgroundScope) { fail("Unexpected save failure") }
            runCurrent()
            f.backend.playbackProgress = PlaybackProgress(0, false)
            advanceTimeBy(4999)
            runCurrent()
            assertEquals(
                123L,
                f
                    .state()
                    .snapshot!!
                    .target.positionMs
            )
            val next = f.submit("pending")
            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                0L,
                f
                    .state()
                    .snapshot!!
                    .target.positionMs
            )
            assertFalse(
                f
                    .state()
                    .snapshot!!
                    .target.playWhenReady
            )
            assertEquals(next, f.state().pending!!.requestId)
            f.backend.playbackProgress = PlaybackProgress(700, true)
            f.session.requestProgressSave()
            runCurrent()
            assertEquals(
                700L,
                f
                    .state()
                    .snapshot!!
                    .target.positionMs
            )
            f.session.exit()
        }

    @Test fun failedSavesRetryLatestProgressNoticeOncePerStreakAndRefreshTtlOnlyOnSuccess() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("active"))
            val original = f.state().snapshot!!
            var notices = 0
            f.session.startSnapshotWriter(backgroundScope) { notices++ }
            runCurrent()
            f.blob.failWrite = true
            f.now = 2000
            repeat(2) {
                f.backend.playbackProgress = PlaybackProgress(1000L + it, true)
                advanceTimeBy(5000)
                runCurrent()
            }
            assertEquals(1, notices)
            assertEquals(original, f.state().snapshot)
            assertEquals(0, f.leases.single().releases)
            f.blob.failWrite = false
            f.now = 4000
            f.backend.playbackProgress = PlaybackProgress(4000, false)
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(
                4000L,
                f
                    .state()
                    .snapshot!!
                    .target.positionMs
            )
            assertEquals(4000L, f.state().snapshot!!.savedAtMs)
            assertTrue(f.state().snapshot!!.expiresAtMs > original.expiresAtMs)
            f.blob.failWrite = true
            f.session.requestProgressSave()
            runCurrent()
            assertEquals(2, notices)
            f.blob.failWrite = false
            f.session.exit()
        }

    @Test fun pendingSamplesAreConflatedWhileOneWriteIsInFlight() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("active"))
            var sample: PlaybackSnapshotTarget = f.session.captureProgress()!!
            val ready = CompletableDeferred<Unit>()
            val saved = mutableListOf<Long>()
            val writer =
                PlaybackSnapshotWriter(backgroundScope, { sample }, {
                    saved.add(it.positionMs)
                    if (saved.size == 1) ready.await()
                    f.session.saveProgress(it)
                }, { fail("Unexpected failure") })
            writer.requestSave()
            runCurrent()
            sample = sample.copy(positionMs = 200)
            writer.requestSave()
            sample = sample.copy(positionMs = 300)
            writer.requestSave()
            ready.complete(Unit)
            runCurrent()
            assertEquals(listOf(123L, 300L), saved)
            assertEquals(
                300L,
                f
                    .state()
                    .snapshot!!
                    .target.positionMs
            )
            writer.stop()
            f.session.exit()
        }

    @Test fun samplesFromAnOldRevisionOrExitedSessionCannotOverwriteOrReviveSnapshot() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("old"))
            val obsolete = f.session.captureProgress()!!
            f.session.deliver(f.submit("new"))
            assertEquals(SnapshotSaveResult.STALE, f.session.saveProgress(obsolete))
            val current = f.session.captureProgress()!!
            val pending = f.submit("pending")
            f.session.exit()
            assertEquals(SnapshotSaveResult.STALE, f.session.saveProgress(current))
            assertNull(f.state().snapshot)
            assertEquals(pending, f.state().pending!!.requestId)
        }

    @Test fun exitOrdersCleanupAfterAnAlreadyStartedWriteEvenIfItsCallerIsCancelled() =
        runBlocking {
            val f = PlaybackSessionTestFixture(Dispatchers.Unconfined, Dispatchers.IO)
            f.session.deliver(f.submit("active"))
            val pending = f.submit("pending")
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            f.blob.beforeWrite = { value ->
                if (value != null &&
                    f
                        .decode(value)
                        .snapshot
                        ?.target
                        ?.positionMs == 900L
                ) {
                    entered.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            val sample = f.session.captureProgress()!!.copy(positionMs = 900)
            val saving = launch { f.session.saveProgress(sample) }
            try {
                entered.await()
                val exiting = async { f.session.exit() }
                saving.cancel()
                release.countDown()
                saving.join()
                assertTrue(exiting.await().detached)
                assertNull(f.state().snapshot)
                assertEquals(pending, f.state().pending!!.requestId)
            } finally {
                release.countDown()
                f.session.exit()
            }
        }
}
