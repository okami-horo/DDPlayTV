package com.xyoye.player_component.playback.session

import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackBackendLifecycleTest {
    @Test fun recreationKeepsSourceAndLiveZeroPositionWithoutResolvingOrConsumingAgain() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val request = f.submit("active")
            f.session.deliver(request)
            val active = f.session.runtime!!
            f.backend.playbackProgress = PlaybackProgress(0, false)

            assertTrue(f.session.unbindBackend(f.backend))
            assertNull(f.backend.current)
            assertEquals(0, f.leases.single().releases)
            assertEquals(active.version, f.session.captureProgress()!!.version)
            val rebuilt = SessionTestBackend()
            assertTrue(f.session.bindBackend(rebuilt) is SessionRequestResult.Activated)
            assertSame(active.playback.source, rebuilt.current)
            assertEquals(PlaybackProgress(0, false), rebuilt.progress())
            assertEquals(SessionRequestResult.Ignored, f.session.open(request, PlaybackRecoveryContext(null, request)))
            assertEquals(1, f.leases.size)

            // A delayed old Activity teardown must not detach its replacement.
            assertTrue(f.session.unbindBackend(f.backend))
            assertSame(active.playback.source, rebuilt.current)
            f.session.exit()
            assertNull(rebuilt.current)
            assertEquals(1, f.leases.single().releases)
        }

    @Test fun preparedReplacementWaitsForNewBackendBeforeConsumingRequest() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("old"))
            val gate = CompletableDeferred<Unit>()
            f.resolve = {
                gate.await()
                PlaybackResolutionResult.Success(f.resolved(it))
            }
            val next = f.submit("new")
            val delivery = launch { f.session.deliver(next) }
            runCurrent()
            f.session.unbindBackend(f.backend)
            gate.complete(Unit)
            runCurrent()
            assertFalse(delivery.isCompleted)
            assertEquals(next, f.state().pending!!.requestId)
            assertEquals(
                "old",
                f.session.runtime!!
                    .playback.source
                    .getVideoTitle()
            )

            val rebuilt = SessionTestBackend()
            f.session.bindBackend(rebuilt)
            delivery.join()
            assertEquals(listOf("old", "new"), rebuilt.applied)
            assertNull(f.state().pending)
            assertEquals(1, f.leases.first().releases)
            f.session.exit()
            assertTrue(f.leases.all { it.releases == 1 })
        }

    @Test fun explicitExitUnblocksReplacementAndClosesAllDetachedResources() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("old"))
            f.session.unbindBackend(f.backend)
            val delivery = launch { f.session.deliver(f.submit("new")) }
            runCurrent()
            assertFalse(delivery.isCompleted)

            val exit = f.session.exit()
            delivery.join()
            assertTrue(exit.detached)
            assertNull(f.session.runtime)
            assertNull(f.state().snapshot)
            assertNull(f.state().pending)
            assertTrue(f.leases.all { it.releases == 1 })
            assertEquals(SessionRequestResult.Ignored, f.session.bindBackend(SessionTestBackend()))
        }

    @Test fun recreationAdoptsAlreadyCurrentNativeCandidateEvenWhenDiskIsStale() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("old"))
            val next = PreparedPlayback(f.resolved(f.descriptor("next")), null)
            f.session.queueNative(f.session.runtime!!.version, next)
            f.backend.transition()
            f.backend.playbackProgress = PlaybackProgress(27, true)
            f.blob.failWrite = true

            f.session.unbindBackend(f.backend)
            val rebuilt = SessionTestBackend()
            f.session.bindBackend(rebuilt)
            assertSame(next.source, rebuilt.current)
            assertEquals(PlaybackProgress(27, true), rebuilt.progress())
            assertEquals(
                2L,
                f.session.runtime!!
                    .version.sourceRevision
            )
            assertEquals(
                1L,
                f
                    .state()
                    .snapshot!!
                    .target.version.sourceRevision
            )
            assertEquals(1, f.leases.first().releases)
            assertEquals(0, f.leases.last().releases)
            f.blob.failWrite = false
            f.session.exit()
        }

    @Test fun failedDetachCannotReleaseOrTransferBorrowedSource() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("old"))
            f.backend.failDetach = true
            assertFalse(f.session.unbindBackend(f.backend))
            val rebuilt = SessionTestBackend()
            assertTrue(f.session.bindBackend(rebuilt) is SessionRequestResult.Rejected)
            assertNull(rebuilt.current)
            assertEquals(0, f.leases.single().releases)
            assertSame(
                f.session.runtime!!
                    .playback.source,
                f.backend.current
            )
            f.backend.failDetach = false
            f.session.exit()
        }
}
