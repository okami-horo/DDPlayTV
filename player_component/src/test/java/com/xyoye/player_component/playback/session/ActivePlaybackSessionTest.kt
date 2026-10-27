package com.xyoye.player_component.playback.session

import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackQueueContext
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import com.xyoye.common_component.source.handoff.ResolvedPlayback
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.StorageSort
import com.xyoye.player_component.playback.handoff.PlaybackHandoffStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
class ActivePlaybackSessionTest {
    @Test fun queuePositionUsesStableIdentityWhenResolverStripsQueueContextFromItems() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val selected = PlaybackFileReference("selected", "/selected.mkv", displayTitle = "Selected")
            val context = PlaybackQueueContext(null, StorageSort.NAME, true, selected.uniqueKey, listOf(selected))
            val descriptor = StoredLibraryFileDescriptor(1, MediaType.LOCAL_STORAGE, selected, context)
            val source = f.resolved(f.descriptor("selected"))
            val resolved =
                ResolvedPlayback(
                    source.source,
                    descriptor,
                    source.identity,
                    listOf(
                        descriptor.copy(file = PlaybackFileReference("previous", "/previous.mkv"), queueContext = null),
                        descriptor.copy(file = selected.copy(displayTitle = null), queueContext = null)
                    )
                )
            val prepared = PreparedPlayback(resolved, null)
            try {
                assertEquals(1, prepared.queueIndex)
            } finally {
                prepared.release()
            }
        }

    @Test fun initialActivationCommitsBeforeApplyAndDuplicateDeliveryDoesNothing() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val id = f.submit("first", autoplay = false)
            f.backend.beforeApply = { next, version ->
                val disk = f.state().snapshot!!.target
                assertEquals(version, disk.version)
                assertEquals(next.resolved.descriptor, disk.descriptor)
                assertEquals(123L, disk.positionMs)
                assertFalse(disk.playWhenReady)
                assertNull(f.state().pending)
            }
            val activated = f.session.deliver(id) as SessionRequestResult.Activated
            assertEquals(PlaybackSessionVersion(id, 1), activated.version)
            assertEquals(SessionRequestResult.Ignored, f.session.deliver(id))
            assertEquals(listOf("first"), f.backend.applied)
            assertEquals(
                0,
                f.session.runtime!!
                    .playback.queueIndex
            )
            assertEquals(0, f.leases.single().releases)
            f.session.exit()
            assertEquals(1, f.leases.single().releases)
        }

    @Test fun slowCancelledACompletingAfterFastBRetiresOnlyA() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val slow = CompletableDeferred<Unit>()
            f.resolve = { descriptor ->
                val result = f.resolved(descriptor)
                if (f.name(descriptor) == "A") withContext(NonCancellable) { slow.await() }
                PlaybackResolutionResult.Success(result)
            }
            val a = f.submit("A")
            val old = launch { f.session.deliver(a) }
            runCurrent()
            val b = f.submit("B")
            assertTrue(f.session.deliver(b) is SessionRequestResult.Activated)
            slow.complete(Unit)
            old.join()
            assertEquals(listOf("B"), f.backend.applied)
            assertEquals(1, f.leases.first().releases)
            assertEquals(0, f.leases.last().releases)
            assertEquals(
                b,
                f
                    .state()
                    .snapshot!!
                    .target.requestId
            )
            f.session.exit()
        }

    @Test fun expiryDuringPreparationKeepsOldPlaybackAndReleasesCandidate() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("old"))
            f.resolve = { descriptor ->
                f.now += PlaybackHandoffStore.PENDING_TTL_MS
                PlaybackResolutionResult.Success(f.resolved(descriptor))
            }
            val result = f.session.deliver(f.submit("expired")) as SessionRequestResult.Rejected
            assertEquals(PlaybackPreparationFailure.RequestExpired, result.failure)
            assertEquals(listOf("old"), f.backend.applied)
            assertEquals(listOf(0, 1), f.leases.map { it.releases })
            assertEquals(
                "old",
                f.name(
                    f
                        .state()
                        .snapshot!!
                        .target.descriptor
                )
            )
            f.session.exit()
        }

    @Test fun saveAndCryptoFailureDoNotTouchExistingPlaybackAndInitialFailureStaysEmpty() =
        runTest {
            for (initial in listOf(true, false)) {
                for (crypto in listOf(true, false)) {
                    val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
                    if (!initial) f.session.deliver(f.submit("old"))
                    val before = f.state().snapshot
                    val id = f.submit("rejected")
                    f.cipher.fail = crypto
                    f.blob.failWrite = !crypto
                    val result = f.session.deliver(id) as SessionRequestResult.Rejected
                    assertEquals(PlaybackPreparationFailure.RecoveryStatePersistenceFailed, result.failure)
                    assertEquals(if (initial) emptyList<String>() else listOf("old"), f.backend.applied)
                    assertEquals(before, f.state().snapshot)
                    assertEquals(1, f.leases.last().releases)
                    if (!initial) assertEquals(0, f.leases.first().releases)
                    f.cipher.fail = false
                    f.blob.failWrite = false
                    f.session.exit()
                }
            }
        }

    @Test fun duplicateDuringPrepareAndStaleDeliveryCannotCancelTheCurrentRequest() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val ready = CompletableDeferred<Unit>()
            f.resolve = {
                ready.await()
                PlaybackResolutionResult.Success(f.resolved(it))
            }
            val obsolete = f.submit("obsolete")
            val current = f.submit("current")
            val running = async { f.session.deliver(current) }
            runCurrent()
            assertEquals(SessionRequestResult.Ignored, f.session.deliver(current))
            assertEquals(
                PlaybackPreparationFailure.RequestSuperseded,
                (f.session.deliver(obsolete) as SessionRequestResult.Rejected).failure
            )
            ready.complete(Unit)
            assertTrue(running.await() is SessionRequestResult.Activated)
            assertEquals(listOf("current"), f.backend.applied)
            f.session.exit()
        }

    @Test fun supersededWithoutNewNavigationIsRejectedAtCommitAndNewPendingSurvives() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val old = f.submit("old")
            var newer = ""
            f.resolve = { descriptor ->
                newer = f.submit("new")
                PlaybackResolutionResult.Success(f.resolved(descriptor))
            }
            assertEquals(PlaybackPreparationFailure.RequestSuperseded, (f.session.deliver(old) as SessionRequestResult.Rejected).failure)
            assertTrue(f.backend.applied.isEmpty())
            assertEquals(newer, f.state().pending!!.requestId)
            assertEquals(1, f.leases.single().releases)
            f.session.exit()
            assertEquals(newer, f.state().pending!!.requestId)
        }

    @Test fun cancellationAfterResolverHandoverAndExplicitExitDoNotLeakLateResources() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("active"))
            val ready = CompletableDeferred<Unit>()
            f.resolve = { descriptor ->
                val result = f.resolved(descriptor)
                withContext(NonCancellable) { ready.await() }
                PlaybackResolutionResult.Success(result)
            }
            val preparing = launch { f.session.deliver(f.submit("late")) }
            runCurrent()
            val newer = f.submit("newer")
            assertTrue(f.session.exit().detached)
            ready.complete(Unit)
            preparing.join()
            assertEquals(listOf(1, 1), f.leases.map { it.releases })
            assertNull(f.state().snapshot)
            assertEquals(newer, f.state().pending!!.requestId)
            assertEquals(listOf("active"), f.backend.applied)
        }

    @Test fun failedBackendApplyRetainsBorrowedSourcesAndStaleErrorCannotEndNewerPlayback() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val initial = f.session.deliver(f.submit("old")) as SessionRequestResult.Activated
            f.backend.failAfterAttach = true
            val failed = f.session.deliver(f.submit("failed")) as SessionRequestResult.BackendFailed
            assertEquals(initial.version.copy(sourceRevision = 2), failed.version)
            assertEquals(listOf(1, 0), f.leases.map { it.releases })
            f.backend.failAfterAttach = false
            f.session.deliver(f.submit("new"))
            assertFalse(f.session.exit(failed.version).detached)
            assertEquals("new", f.backend.current!!.getVideoTitle())
            assertEquals(listOf(1, 1, 0), f.leases.map { it.releases })
            f.session.exit()
            assertEquals(listOf(1, 1, 1), f.leases.map { it.releases })
        }

    @Test fun failureBeforeBackendAttachmentLeavesNewRecoveryTargetAndActualOldRuntimeDistinct() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("old"))
            f.backend.beforeApply = { _, _ -> error("apply failed") }
            assertTrue(f.session.deliver(f.submit("target")) is SessionRequestResult.BackendFailed)
            assertEquals(
                "old",
                f.session.runtime!!
                    .playback.source
                    .getVideoTitle()
            )
            assertEquals(
                "target",
                f.name(
                    f
                        .state()
                        .snapshot!!
                        .target.descriptor
                )
            )
            assertEquals(listOf(0, 1), f.leases.map { it.releases })
            f.backend.beforeApply = { _, _ -> }
            f.session.deliver(f.submit("next"))
            assertEquals(
                3L,
                f.session.runtime!!
                    .version.sourceRevision
            )
            f.session.exit()
        }

    @Test fun sourceRetirementWaitsForBackendDetachmentAndAddonFailureStillReleasesLease() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.addonThrows = true
            f.session.deliver(f.submit("old"))
            f.backend.retainOld = true
            f.session.deliver(f.submit("new"))
            assertEquals(listOf(0, 0), f.leases.map { it.releases })
            f.backend.failDetach = true
            assertFalse(f.session.exit().detached)
            assertEquals(listOf(0, 0), f.leases.map { it.releases })
            f.backend.failDetach = false
            assertTrue(f.session.exit().detached)
            f.session.exit()
            assertEquals(listOf(1, 1), f.leases.map { it.releases })
            assertEquals(2, f.addonReleases)
        }

    @Test fun nativeCandidateIsMemoryOnlyAndTransitionFailureDoesNotStopPlaybackOrBlockControlledSwitch() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val first = f.session.deliver(f.submit("old")) as SessionRequestResult.Activated
            val before = f.blob.data
            val next = PreparedPlayback(f.resolved(f.descriptor("native")), null)
            f.blob.failWrite = true
            assertTrue(f.session.queueNative(first.version, next))
            assertEquals(before, f.blob.data)
            f.backend.transition()
            f.session.nativeTransition(next.candidateId)
            assertEquals(
                "native",
                f.session.runtime!!
                    .playback.source
                    .getVideoTitle()
            )
            assertEquals(
                2L,
                f.session.runtime!!
                    .version.sourceRevision
            )
            assertEquals(
                1L,
                f.session.committedTarget!!
                    .version.sourceRevision
            )
            assertTrue(f.session.recoverySaveFailed)
            assertEquals(listOf(1, 0), f.leases.map { it.releases })
            f.blob.failWrite = false
            f.session.deliver(f.submit("controlled"))
            assertEquals(
                3L,
                f
                    .state()
                    .snapshot!!
                    .target.version.sourceRevision
            )
            assertEquals(listOf("old", "controlled"), f.backend.applied)
            f.session.exit()
        }

    @Test fun delayedNativeCallbackIsReconciledBeforeNewPreparationAndCommit() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val first = f.session.deliver(f.submit("old")) as SessionRequestResult.Activated
            val next = PreparedPlayback(f.resolved(f.descriptor("native")), null)
            f.session.queueNative(first.version, next)
            f.backend.transition()
            f.resolve = {
                assertEquals(
                    "native",
                    f.session.runtime!!
                        .playback.source
                        .getVideoTitle()
                )
                assertEquals(
                    0L,
                    f
                        .state()
                        .snapshot!!
                        .target.positionMs
                )
                assertEquals(0, f.leases[1].releases)
                PlaybackResolutionResult.Success(f.resolved(it))
            }
            f.session.deliver(f.submit("replacement"))
            f.session.nativeTransition(next.candidateId)
            assertEquals(
                3L,
                f.session.runtime!!
                    .version.sourceRevision
            )
            assertEquals(listOf(1, 1, 0), f.leases.map { it.releases })
            f.session.exit()
        }

    @Test fun attachedQueueBlocksCommitAndFailedEnqueueRequiresConfirmedRemoval() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val first = f.session.deliver(f.submit("old")) as SessionRequestResult.Activated
            val next = PreparedPlayback(f.resolved(f.descriptor("queued")), null)
            f.backend.rejectEnqueue = true
            f.backend.blockRemoval = true
            assertFalse(f.session.queueNative(first.version, next))
            assertEquals(listOf(0, 0), f.leases.map { it.releases })
            assertTrue(f.session.deliver(f.submit("blocked")) is SessionRequestResult.Rejected)
            assertEquals(listOf("old"), f.backend.applied)
            f.backend.blockRemoval = false
            f.session.deliver(f.submit("allowed"))
            assertEquals(listOf(1, 1, 0), f.leases.map { it.releases })
            f.session.exit()
        }

    @Test fun repeatedNativeOwnershipTransferCannotReleaseAnAttachedCandidate() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val active = f.session.deliver(f.submit("old")) as SessionRequestResult.Activated
            val next = PreparedPlayback(f.resolved(f.descriptor("queued")), null)
            f.session.queueNative(active.version, next)
            try {
                f.session.queueNative(active.version, next)
                fail("Ownership must transfer once")
            } catch (_: IllegalStateException) {
            }
            assertEquals(listOf(0, 0), f.leases.map { it.releases })
            f.session.exit()
            assertEquals(listOf(1, 1), f.leases.map { it.releases })
        }

    @Test fun exitClearsRuntimeEvenWhenSnapshotDeletionFailsAndPreservesNewPending() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("old"))
            val pending = f.submit("new")
            f.blob.failWrite = true
            val result = f.session.exit()
            assertTrue(result.detached)
            assertTrue(result.cleanupFailed)
            assertNull(f.session.runtime)
            assertNull(f.session.committedTarget)
            assertEquals(pending, f.state().pending!!.requestId)
            assertNotNull(f.state().snapshot)
            assertEquals(1, f.leases.single().releases)
        }

    @Test fun cancelledNativeHandoverBeforePlaybackDispatchReleasesItsSource() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val active = f.session.deliver(f.submit("active")) as SessionRequestResult.Activated
            val next = PreparedPlayback(f.resolved(f.descriptor("cancelled")), null)
            val dispatch = launch(Dispatchers.Unconfined) { f.session.queueNative(active.version, next) }
            dispatch.cancelAndJoin()
            advanceUntilIdle()
            assertEquals(1, f.leases.last().releases)
            f.session.exit()
        }

    @Test fun newRequestAndCallerCancellationDuringCommitCannotAbandonCommittedApplication() =
        runBlocking {
            val f = PlaybackSessionTestFixture(Dispatchers.Unconfined, Dispatchers.IO)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val id = f.submit("A")
            f.blob.beforeWrite = { value ->
                if (value != null &&
                    f
                        .decode(value)
                        .snapshot
                        ?.target
                        ?.requestId == id
                ) {
                    entered.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            val first = launch { f.session.deliver(id) }
            try {
                entered.await()
                assertNull(f.state().snapshot)
                val second = async { f.session.deliver(f.submit("B")) }
                first.cancel()
                release.countDown()
                first.join()
                assertTrue(second.await() is SessionRequestResult.Activated)
                assertEquals(listOf("A", "B"), f.backend.applied)
                assertEquals(listOf(1, 0), f.leases.map { it.releases })
                assertEquals(
                    PlaybackSessionVersion(id, 2),
                    f
                        .state()
                        .snapshot!!
                        .target.version
                )
            } finally {
                release.countDown()
                f.session.exit()
            }
        }
}
