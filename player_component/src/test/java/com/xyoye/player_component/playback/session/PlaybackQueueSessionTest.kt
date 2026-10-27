package com.xyoye.player_component.playback.session

import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.playback.handoff.PlaybackQueueContext
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import com.xyoye.common_component.source.handoff.ResolvedPlayback
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.StorageSort
import com.xyoye.player_component.playback.handoff.HandoffStoreResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
class PlaybackQueueSessionTest {
    @Test fun internalSelectionPersistsDirectoryOrderAndSelectedFallbackForProcessRecovery() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val files = listOf("one", "two", "three").map { PlaybackFileReference(it, "/$it.mkv", displayTitle = "$it.mkv") }
            val context = PlaybackQueueContext(PlaybackFileReference("directory", "/"), StorageSort.SIZE, false, "one", files, false)
            val initial = StoredLibraryFileDescriptor(7, MediaType.LOCAL_STORAGE, files.first(), context)
            val queue = files.map { initial.copy(file = it, queueContext = null) }
            f.resolve = {
                val selected = it as StoredLibraryFileDescriptor
                val resolved = f.resolved(f.descriptor(selected.file.uniqueKey))
                PlaybackResolutionResult.Success(ResolvedPlayback(resolved.source, selected, resolved.identity, queue))
            }
            val request =
                (
                    f.store.submit(
                        PlaybackRequestInput(initial, null, PlaybackRequestOrigin.STORAGE_FILE)
                    ) as HandoffStoreResult.Success
                ).value.requestId
            f.session.deliver(request)
            f.session.selectQueueItem(1, f.session.runtime!!.version)
            val persisted =
                f
                    .state()
                    .snapshot!!
                    .target.descriptor as StoredLibraryFileDescriptor
            assertEquals(context.copy(selectedUniqueKey = "two"), persisted.queueContext)
            assertEquals("two", persisted.file.uniqueKey)
            assertEquals(1, f.backend.current!!.getGroupIndex())
            assertEquals("three.mkv", f.backend.current!!.indexTitle(2))
            f.session.exit()
        }

    @Test fun manualSelectionKeepsSessionRequestAndQueueWithoutConsumingExternalPending() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            queue(f)
            val request = f.submit("one")
            f.session.deliver(request)
            val pending = f.submit("external")
            val first = f.session.runtime!!
            assertTrue(f.session.selectQueueItem(1, first.version) is SessionRequestResult.Activated)
            val next = f.session.runtime!!
            assertEquals(first.version.sessionId, next.version.sessionId)
            assertEquals(request, next.requestId)
            assertEquals(2L, next.version.sourceRevision)
            assertEquals(1, next.playback.queueIndex)
            assertEquals(3, next.playback.resolved.queue.size)
            assertEquals(pending, f.state().pending!!.requestId)
            assertEquals("two", f.backend.current!!.getVideoTitle())
            assertEquals(1, f.leases.first().releases)
            f.session.exit()
        }

    @Test fun failedInternalSaveKeepsOldPlaybackAndClosesOnlyCandidate() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            queue(f)
            f.session.deliver(f.submit("one"))
            val previous = f.session.runtime!!
            f.blob.failWrite = true
            assertTrue(f.session.selectQueueItem(1, previous.version) is SessionRequestResult.Rejected)
            assertSame(previous, f.session.runtime)
            assertEquals(listOf("one"), f.backend.applied)
            assertEquals(listOf(0, 1), f.leases.map { it.releases })
            f.blob.failWrite = false
            f.session.exit()
        }

    @Test fun externalRequestCancelsSlowInternalResolutionWithoutPausingOldPlayback() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            queue(f)
            f.session.deliver(f.submit("one"))
            val previous = f.session.runtime!!
            val gate = CompletableDeferred<Unit>()
            val resolve = f.resolve
            f.resolve = {
                if (f.name(it) == "two") gate.await()
                resolve(it)
            }
            val internal = async { f.session.selectQueueItem(1, previous.version) }
            runCurrent()
            assertSame(previous.playback.source, f.backend.current)
            assertTrue(f.backend.progress().playWhenReady)
            f.session.deliver(f.submit("external"))
            internal.join()
            assertTrue(internal.isCancelled)
            assertEquals("external", f.backend.current!!.getVideoTitle())
            f.session.exit()
        }

    @Test fun stableQueuePreloadNeedsNoWriteAndTransitionAllowsLaterControlledSwitchWithStaleDisk() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            queue(f)
            f.session.deliver(f.submit("one"))
            val first = f.session.runtime!!
            f.blob.failWrite = true
            assertTrue(f.session.prepareNextNative(first.version))
            val candidate = f.backend.queued!!
            assertEquals(1, candidate.queueIndex)
            f.backend.transition()
            f.session.nativeTransition(candidate.candidateId)
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
            f.blob.failWrite = false
            assertTrue(f.session.selectQueueItem(2, f.session.runtime!!.version) is SessionRequestResult.Activated)
            assertEquals("three", f.backend.current!!.getVideoTitle())
            assertEquals(
                3L,
                f
                    .state()
                    .snapshot!!
                    .target.version.sourceRevision
            )
            assertEquals(first.requestId, f.session.runtime!!.requestId)
            assertEquals(listOf("one", "three"), f.backend.applied)
            f.session.exit()
        }

    @Test fun endOfQueueAndRepeatedPreloadDoNotResolveOrAcquireExtraSources() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            queue(f)
            f.session.deliver(f.submit("three"))
            val active = f.session.runtime!!
            assertFalse(f.session.prepareNextNative(active.version))
            assertTrue(f.session.selectQueueItem(3, active.version) is SessionRequestResult.Rejected)
            assertEquals(1, f.leases.size)
            f.session.selectQueueItem(0, active.version)
            val current = f.session.runtime!!
            assertTrue(f.session.prepareNextNative(current.version))
            assertFalse(f.session.prepareNextNative(current.version))
            assertEquals(3, f.leases.size)
            f.session.exit()
        }

    @Test fun newProcessRecoversPreviousEpisodeUntilNativeSnapshotRetrySucceeds() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val f = PlaybackSessionTestFixture(dispatcher)
            queue(f)
            val id = f.submit("one")
            f.session.deliver(id)
            f.session.prepareNextNative(f.session.runtime!!.version)
            val candidate = f.backend.queued!!
            f.blob.failWrite = true
            f.backend.transition()
            f.session.nativeTransition(candidate.candidateId)

            suspend fun recover(
                expectedName: String,
                revision: Long,
                position: Long
            ) {
                val process = PlaybackSessionTestFixture(dispatcher)
                process.blob.data = f.blob.data
                val result = process.session.open(null, PlaybackRecoveryContext(id, id))
                assertTrue(result is SessionRequestResult.Activated)
                assertEquals(expectedName, process.backend.current!!.getVideoTitle())
                assertEquals(
                    revision,
                    process.session.runtime!!
                        .version.sourceRevision
                )
                assertEquals(position, process.backend.progress().positionMs)
                process.session.exit()
            }

            recover("one", 1, 123)
            f.blob.failWrite = false
            f.backend.playbackProgress = PlaybackProgress(77, true)
            assertEquals(SnapshotSaveResult.SAVED, f.session.saveProgress(f.session.captureProgress()!!))
            recover("two", 2, 77)
            assertEquals(0, f.leases.last().releases)
            f.session.exit()
        }

    private fun queue(f: PlaybackSessionTestFixture) {
        val descriptors = listOf("one", "two", "three").map(f::descriptor)
        f.resolve = {
            val resolved = f.resolved(it)
            PlaybackResolutionResult.Success(ResolvedPlayback(resolved.source, resolved.descriptor, resolved.identity, descriptors))
        }
    }
}
