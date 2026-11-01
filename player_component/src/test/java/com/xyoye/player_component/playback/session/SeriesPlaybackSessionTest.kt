package com.xyoye.player_component.playback.session

import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.SeriesPlaybackDescriptor
import com.xyoye.common_component.playback.handoff.SeriesQueueEntry
import com.xyoye.common_component.playback.handoff.SeriesQueueFile
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import com.xyoye.common_component.source.handoff.ResolvedPlayback
import com.xyoye.player_component.playback.handoff.HandoffStoreResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SeriesPlaybackSessionTest {
    @Test fun versionPromptPreservesPlaybackAndExternalPendingThenConfirmedChoiceChangesPreferredSource() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val descriptor = series(f)
            val id = submit(f, descriptor)
            f.session.deliver(id)
            val old = f.session.runtime!!
            val pending = f.submit("external")
            assertFalse(f.session.prepareNextNative(old.version))
            assertEquals(1, f.leases.size)
            val rejected = f.session.selectQueueItem(1, old.version) as SessionRequestResult.Rejected
            assertTrue(rejected.failure is PlaybackPreparationFailure.VersionSelectionRequired)
            assertSame(old, f.session.runtime)
            assertEquals(pending, f.state().pending!!.requestId)
            assertEquals(0, f.leases.first().releases)
            val chosen = descriptor.entries[1].files.single()
            assertTrue(f.session.selectQueueItem(1, old.version, chosen) is SessionRequestResult.Activated)
            val next = f.session.runtime!!
            val persisted =
                f
                    .state()
                    .snapshot!!
                    .target.descriptor as SeriesPlaybackDescriptor
            assertEquals(2, persisted.preferredLibraryId)
            assertEquals(chosen, persisted.selectedFile)
            assertEquals(1, next.playback.queueIndex)
            assertEquals(3, next.playback.source.getGroupSize())
            assertEquals("S2E1", next.playback.source.indexTitle(1))
            assertNotNull(next.playback.resolved.metadataFile)
            assertEquals(id, next.requestId)
            assertEquals(old.version.sessionId, next.version.sessionId)
            assertEquals(pending, f.state().pending!!.requestId)
            assertTrue(f.session.prepareNextNative(next.version))
            f.session.exit()
        }

    @Test fun staleOrForeignChoiceAndFailedSnapshotNeverReplaceTheCurrentSource() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val descriptor = series(f)
            f.session.deliver(submit(f, descriptor))
            val first = f.session.runtime!!
            val chosen = descriptor.entries[1].files.single()
            assertTrue(f.session.selectQueueItem(1, first.version, descriptor.selectedFile) is SessionRequestResult.Rejected)
            assertEquals(1, f.leases.size)
            f.blob.failWrite = true
            val rejected = f.session.selectQueueItem(1, first.version, chosen) as SessionRequestResult.Rejected
            assertEquals(PlaybackPreparationFailure.RecoveryStatePersistenceFailed, rejected.failure)
            assertSame(first, f.session.runtime)
            assertEquals(listOf(0, 1), f.leases.map { it.releases })
            f.blob.failWrite = false
            f.session.selectQueueItem(1, first.version, chosen)
            val second = f.session.runtime!!
            assertTrue(f.session.selectQueueItem(2, first.version, descriptor.entries[2].files.single()) is SessionRequestResult.Rejected)
            assertSame(second, f.session.runtime)
            f.session.exit()
        }

    @Test fun processRecoveryKeepsWholeManifestChosenVersionCursorAndProgress() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val f = PlaybackSessionTestFixture(dispatcher)
            val descriptor = series(f)
            val id = submit(f, descriptor)
            f.session.deliver(id)
            f.session.selectQueueItem(1, f.session.runtime!!.version, descriptor.entries[1].files.single())
            f.backend.playbackProgress = PlaybackProgress(777, false)
            assertEquals(SnapshotSaveResult.SAVED, f.session.saveProgress(f.session.captureProgress()!!))
            val process = PlaybackSessionTestFixture(dispatcher)
            series(process)
            process.blob.data = f.blob.data
            assertTrue(process.session.open(null, PlaybackRecoveryContext(id, id)) is SessionRequestResult.Activated)
            val recovered = process.session.runtime!!.playback
            assertEquals(1, recovered.queueIndex)
            assertEquals(3, recovered.resolved.queue.size)
            assertEquals(2, (recovered.resolved.descriptor as SeriesPlaybackDescriptor).preferredLibraryId)
            assertEquals(777, process.backend.progress().positionMs)
            assertFalse(process.backend.progress().playWhenReady)
            process.session.exit()
            f.session.exit()
        }

    @Test fun mergedAlternativeRebuildsQueueAndCannotReplayTheSamePhysicalFile() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val original = series(f)
            val merged = original.entries[1].files.single()
            val descriptor = original.copy(entries = original.entries.take(2) + original.entries[2].copy(files = listOf(merged)))
            f.session.deliver(submit(f, descriptor))
            f.session.selectQueueItem(2, f.session.runtime!!.version, merged)
            val active = f.session.runtime!!.playback
            assertEquals(listOf(0, 2), active.resolved.queue.map { (it as SeriesPlaybackDescriptor).selectedEpisode })
            assertEquals(1, active.queueIndex)
            assertEquals(2, active.source.getGroupSize())
            assertFalse(f.session.prepareNextNative(f.session.runtime!!.version))
            f.session.exit()
        }

    private suspend fun submit(
        f: PlaybackSessionTestFixture,
        descriptor: SeriesPlaybackDescriptor
    ): String =
        (f.store.submit(PlaybackRequestInput(descriptor, null, PlaybackRequestOrigin.ANIME_EPISODE)) as HandoffStoreResult.Success)
            .value.requestId

    private fun series(f: PlaybackSessionTestFixture): SeriesPlaybackDescriptor {
        val files =
            listOf("one", "two", "three").mapIndexed { index, name ->
                val stored = f.descriptor(name)
                SeriesQueueFile(index + 1L, 1, if (index == 0) 1 else 2, 1, stored.mediaType, stored.file, "Source")
            }
        val entries =
            files.mapIndexed { index, file ->
                SeriesQueueEntry(
                    if (index ==
                        0
                    ) {
                        1
                    } else {
                        2
                    },
                    if (index == 0) 12 else index,
                    "S${if (index == 0) 1 else 2}E${if (index == 0) 12 else index}",
                    listOf(file)
                )
            }
        f.resolve = { value ->
            val target = value as SeriesPlaybackDescriptor
            val options = target.entries[target.selectedEpisode].files
            val selected = target.selectedFile ?: options.filter { it.libraryId == target.preferredLibraryId }.singleOrNull()
            if (selected == null) {
                PlaybackResolutionResult.Failure(PlaybackPreparationFailure.VersionSelectionRequired(target, options))
            } else {
                val normalized = target.copy(preferredLibraryId = selected.libraryId, selectedFile = selected)
                val resolved = f.resolved(StoredLibraryFileDescriptor(selected.libraryId, selected.mediaType, selected.file))
                PlaybackResolutionResult.Success(
                    ResolvedPlayback(
                        resolved.source,
                        normalized,
                        resolved.identity.copy(displayTitle = normalized.displayTitle()),
                        normalized.queue(),
                        resolved.metadataFile
                    )
                )
            }
        }
        return SeriesPlaybackDescriptor(77, entries, 0, 1, files.first())
    }
}
