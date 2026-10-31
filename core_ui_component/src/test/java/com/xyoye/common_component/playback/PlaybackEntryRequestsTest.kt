package com.xyoye.common_component.playback

import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlaybackEntryRequestsTest {
    @Test fun lateOldSubmissionCannotNavigateAfterNewerActionAndConsumptionDoesNotReplay() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val requests =
                PlaybackEntryRequests(this) { input ->
                    val id = (input.descriptor as StoredLibraryFileDescriptor).file.storagePath
                    if (id == "old") withContext(NonCancellable) { gate.await() }
                    PlaybackSubmissionResult.Accepted(id)
                }
            val old = requests.submit { input("old") }
            yield()
            requests.submit { input("new") }.join()
            assertEquals(PlaybackSubmissionResult.Accepted("new"), requests.outcomes.first())
            gate.complete(Unit)
            old.join()
            val again = async(start = CoroutineStart.UNDISPATCHED) { requests.outcomes.first() }
            yield()
            assertFalse("Consumed or superseded results must not replay after UI recreation", again.isCompleted)
            again.cancel()
        }

    @Test fun rejectedSubmissionProducesNoNavigationIdAndNewBackgroundResultReplacesOld() =
        runBlocking {
            val requests =
                PlaybackEntryRequests(this) { input ->
                    if ((input.descriptor as StoredLibraryFileDescriptor).file.storagePath == "failed") {
                        PlaybackSubmissionResult.Rejected(PlaybackPreparationFailure.RecoveryStatePersistenceFailed)
                    } else {
                        PlaybackSubmissionResult.Accepted("accepted")
                    }
                }
            requests.submit { input("success") }.join()
            requests.submit { input("failed") }.join()
            assertEquals(
                PlaybackSubmissionResult.Rejected(PlaybackPreparationFailure.RecoveryStatePersistenceFailed),
                requests.outcomes.first()
            )
        }

    @Test fun cancellationWhilePreparingDoesNotSubmitOrEmitAndRestoresLoading() =
        runBlocking {
            val loading = mutableListOf<Boolean>()
            var submissions = 0
            val requests =
                PlaybackEntryRequests(this, loading::add) {
                    submissions++
                    PlaybackSubmissionResult.Accepted("unexpected")
                }
            val gate = CompletableDeferred<PlaybackRequestInput>()
            val pending = requests.submit { gate.await() }
            yield()
            pending.cancel()
            pending.join()
            assertEquals(0, submissions)
            assertEquals(listOf(true, false), loading)
            val result = async(start = CoroutineStart.UNDISPATCHED) { requests.outcomes.first() }
            yield()
            assertFalse(result.isCompleted)
            result.cancel()
        }

    @Test fun cancelledNonCooperativeSubmissionCannotPublishNavigation() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val requests =
                PlaybackEntryRequests(this) {
                    withContext(NonCancellable) { gate.await() }
                    PlaybackSubmissionResult.Accepted("cancelled")
                }
            val pending = requests.submit { input("old") }
            yield()
            pending.cancel()
            gate.complete(Unit)
            pending.join()
            val result = async(start = CoroutineStart.UNDISPATCHED) { requests.outcomes.first() }
            yield()
            assertFalse("Cancellation must suppress navigation even if submission returns normally", result.isCompleted)
            result.cancel()
        }

    private fun input(uri: String) =
        PlaybackRequestInput(
            StoredLibraryFileDescriptor(1, MediaType.LOCAL_STORAGE, PlaybackFileReference(uri, uri)),
            null,
            PlaybackRequestOrigin.STORAGE_FILE
        )
}
