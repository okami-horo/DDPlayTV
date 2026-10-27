package com.xyoye.player_component.playback.session

import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import com.xyoye.player_component.playback.handoff.HandoffStoreResult
import com.xyoye.player_component.playback.handoff.PlaybackHandoffStore
import com.xyoye.player_component.playback.handoff.PlaybackSnapshotTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackRecoveryTest {
    @Test fun coldStartAndOrdinaryConsumedRequestCannotRestoreButSystemRecreationCan() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val target = f.seedSnapshot("saved", 0)
            assertEquals(PlaybackPreparationFailure.RequestMissing, (f.session.open(null, null) as SessionRequestResult.Rejected).failure)
            assertEquals(
                PlaybackPreparationFailure.RequestMissing,
                (f.session.open(target.requestId, null) as SessionRequestResult.Rejected).failure
            )
            assertTrue(f.leases.isEmpty())
            assertTrue(f.session.open(target.requestId, PlaybackRecoveryContext(null, target.requestId)) is SessionRequestResult.Activated)
            assertEquals(
                0L,
                f.session.runtime!!
                    .playback.positionMs
            )
            assertEquals(
                123L,
                f.session.runtime!!
                    .playback.source
                    .getCurrentPosition()
            )
            assertEquals(target.version, f.session.runtime!!.version)
            f.session.exit()
        }

    @Test fun savedSessionBindingIsStrictAndFallbackAcceptsOnlyFirstOrLastCommittedRequest() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val first = f.seedSnapshot("first", 10)
            val lastId = f.submit("last")
            val last = first.copy(version = first.version.copy(sourceRevision = 2), requestId = lastId, descriptor = f.descriptor("last"))
            assertTrue(f.store.activate(lastId, last) { true } is HandoffStoreResult.Success)
            assertTrue(f.store.readSnapshotForRecovery(null, first.requestId) is HandoffStoreResult.Success)
            assertTrue(f.store.readSnapshotForRecovery(null, lastId) is HandoffStoreResult.Success)
            assertTrue(f.store.readSnapshotForRecovery(null, "unrelated") is HandoffStoreResult.Failure)
            assertTrue(f.store.readSnapshotForRecovery(null, null) is HandoffStoreResult.Failure)
            val wrong = f.session.open(lastId, PlaybackRecoveryContext("wrong-session", lastId))
            assertEquals(PlaybackPreparationFailure.SessionSnapshotMissing, (wrong as SessionRequestResult.Rejected).failure)
            assertTrue(f.leases.isEmpty())
            assertTrue(f.session.open(lastId, PlaybackRecoveryContext(null, lastId)) is SessionRequestResult.Activated)
            assertEquals(last.version, f.session.runtime!!.version)
            f.session.exit()
        }

    @Test fun validNewPendingWinsAndRetainsTheBoundSessionIdentity() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val old = f.seedSnapshot("old", 40)
            val next = f.submit("new")
            assertTrue(
                f.session.open(next, PlaybackRecoveryContext(old.version.sessionId, old.requestId)) is SessionRequestResult.Activated
            )
            assertEquals(listOf("new"), f.backend.applied)
            assertEquals(old.version.copy(sourceRevision = 2), f.session.runtime!!.version)
            assertEquals(
                next,
                f
                    .state()
                    .snapshot!!
                    .target.requestId
            )
            f.session.exit()
        }

    @Test fun preCommitFailureFallsBackToMatchingSnapshotWithoutUsingHistoryProgress() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val old = f.seedSnapshot("old", 0)
            val next = f.submit("missing")
            f.resolve = {
                if (f.name(it) == "missing") {
                    PlaybackResolutionResult.Failure(PlaybackPreparationFailure.FileMissing)
                } else {
                    PlaybackResolutionResult.Success(f.resolved(it))
                }
            }
            val result =
                f.session.open(
                    next,
                    PlaybackRecoveryContext(old.version.sessionId, old.requestId)
                ) as SessionRequestResult.Activated
            assertEquals(PlaybackPreparationFailure.FileMissing, result.replacedRequestFailure)
            assertEquals(listOf("old"), f.backend.applied)
            assertEquals(0L, f.backend.progress().positionMs)
            assertNull(f.state().pending)
            f.session.exit()
        }

    @Test fun committedBackendFailureDoesNotRollBackToOldSnapshot() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val old = f.seedSnapshot("old", 0)
            val next = f.submit("new")
            f.backend.beforeApply = { _, _ -> error("kernel failed") }
            assertTrue(
                f.session.open(next, PlaybackRecoveryContext(old.version.sessionId, old.requestId)) is SessionRequestResult.BackendFailed
            )
            assertEquals(
                "new",
                f.name(
                    f
                        .state()
                        .snapshot!!
                        .target.descriptor
                )
            )
            assertEquals(1, f.leases.single().releases)
            assertTrue(f.backend.applied.isEmpty())
            f.session.exit()
        }

    @Test fun failedWriteAndCleanupStillAllowOldSnapshotRecoveryAndReportRetainedPending() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val old = f.seedSnapshot("old", 0)
            val next = f.submit("new")
            f.blob.failWrite = true
            val result =
                f.session.open(
                    next,
                    PlaybackRecoveryContext(old.version.sessionId, old.requestId)
                ) as SessionRequestResult.Activated
            assertEquals(PlaybackPreparationFailure.RecoveryStatePersistenceFailed, result.replacedRequestFailure)
            assertTrue(result.cleanupFailed)
            assertEquals(next, f.state().pending!!.requestId)
            assertEquals(old, f.state().snapshot!!.target)
            assertEquals(listOf("old"), f.backend.applied)
            assertEquals(listOf(1, 0), f.leases.map { it.releases })
            f.blob.failWrite = false
            f.session.exit()
        }

    @Test fun liveRuntimeAheadOfDiskSurvivesConfigurationRecreation() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val old = f.session.deliver(f.submit("old")) as SessionRequestResult.Activated
            val next = PreparedPlayback(f.resolved(f.descriptor("native")), null)
            f.session.queueNative(old.version, next)
            f.blob.failWrite = true
            f.backend.transition()
            f.session.nativeTransition(next.candidateId)
            val live = f.session.runtime
            assertEquals(
                SessionRequestResult.Ignored,
                f.session.open(old.version.sessionId, PlaybackRecoveryContext(old.version.sessionId, null))
            )
            assertSame(live, f.session.runtime)
            assertEquals(2L, live!!.version.sourceRevision)
            assertEquals(
                1L,
                f.session.committedTarget!!
                    .version.sourceRevision
            )
            assertEquals(2, f.leases.size)
            f.blob.failWrite = false
            f.session.exit()
        }

    @Test fun restoredPositionsRespectKnownDurationAndNonSeekableDefaults() =
        runTest {
            assertEquals(0L, PlaybackPositionConstraints().normalize(-1))
            assertEquals(70L, PlaybackPositionConstraints().normalize(70))
            assertEquals(49L, PlaybackPositionConstraints(durationMs = 50).normalize(70))
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val saved = f.seedSnapshot("live", 400)
            f.backend.constraints = PlaybackPositionConstraints(seekable = false)
            f.session.open(null, PlaybackRecoveryContext(saved.version.sessionId, null))
            assertEquals(0L, f.backend.progress().positionMs)
            f.session.exit()
        }

    @Test fun snapshotExpiringDuringResolutionCannotActivateAndRetiresResolvedSource() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val saved = f.seedSnapshot("old", 50)
            f.resolve = {
                f.now += PlaybackHandoffStore.SNAPSHOT_TTL_MS
                PlaybackResolutionResult.Success(f.resolved(it))
            }
            val result = f.session.open(null, PlaybackRecoveryContext(saved.version.sessionId, null)) as SessionRequestResult.Rejected
            assertEquals(PlaybackPreparationFailure.SessionSnapshotMissing, result.failure)
            assertEquals(1, f.leases.single().releases)
            assertTrue(f.backend.applied.isEmpty())
            f.session.exit()
        }

    @Test fun newDeliveryCancelsSlowRestorationWithoutClosingTheNewRuntime() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val saved = f.seedSnapshot("old", 0)
            val ready = CompletableDeferred<Unit>()
            f.resolve = {
                val source = f.resolved(it)
                if (f.name(it) == "old") withContext(NonCancellable) { ready.await() }
                PlaybackResolutionResult.Success(source)
            }
            val restoring = launch { f.session.open(null, PlaybackRecoveryContext(saved.version.sessionId, null)) }
            runCurrent()
            val next = f.submit("new")
            f.session.deliver(next)
            ready.complete(Unit)
            restoring.join()
            assertEquals(listOf("new"), f.backend.applied)
            assertEquals(listOf(1, 0), f.leases.map { it.releases })
            assertEquals(
                next,
                f
                    .state()
                    .snapshot!!
                    .target.requestId
            )
            f.session.exit()
        }

    private suspend fun PlaybackSessionTestFixture.seedSnapshot(
        name: String,
        position: Long
    ): PlaybackSnapshotTarget {
        val id = submit(name)
        val target = PlaybackSnapshotTarget(PlaybackSessionVersion(id, 1), id, descriptor(name), null, position, false)
        assertTrue(store.activate(id, target) { true } is HandoffStoreResult.Success)
        return target
    }
}
