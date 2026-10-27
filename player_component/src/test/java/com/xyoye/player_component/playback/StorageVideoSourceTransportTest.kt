package com.xyoye.player_component.playback

import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StorageVideoSourceTransportTest {
    @Test
    fun cancellationBeforeMainThreadAttachmentRetiresCandidate() =
        runTest {
            val source = Source()
            val retired = mutableListOf<BaseVideoSource>()
            val main = StandardTestDispatcher(testScheduler)
            val pending =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    withPreparedPlaybackSource(
                        { source },
                        { withContext(main) { fail("Cancelled attachment ran") } },
                        { false },
                        retired::add
                    )
                }
            pending.cancel()
            advanceUntilIdle()
            assertEquals(listOf(source), retired)
        }

    @Test
    fun latePreparationResultIsRetiredAfterCallerCancellation() =
        runTest {
            val source = Source()
            val ready = CompletableDeferred<Unit>()
            val retired = mutableListOf<BaseVideoSource>()
            val pending =
                launch {
                    withPreparedPlaybackSource(
                        {
                            withContext(NonCancellable) { ready.await() }
                            source
                        },
                        { currentCoroutineContext().ensureActive() },
                        { false },
                        retired::add
                    )
                }
            runCurrent()
            pending.cancel()
            ready.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(source), retired)
        }

    @Test
    fun sameMediaIdentityDoesNotMeanCandidateWasAttached() =
        runTest {
            val active = Source()
            val candidate = Source()
            val retired = mutableListOf<BaseVideoSource>()
            withPreparedPlaybackSource({ candidate }, {}, { it === active }, retired::add)
            assertEquals(listOf(candidate), retired)
        }

    @Test
    fun exceptionAfterAttachmentLeavesRetirementWithBackendOwner() =
        runTest {
            val candidate = Source()
            var attached: BaseVideoSource? = null
            val retired = mutableListOf<BaseVideoSource>()
            try {
                withPreparedPlaybackSource({ candidate }, {
                    attached = it
                    error("UI failure")
                }, { it === attached }, retired::add)
                fail("Expected UI failure")
            } catch (_: IllegalStateException) {
            }
            assertSame(candidate, attached)
            assertEquals(emptyList<BaseVideoSource>(), retired)
        }

    private class Source : BaseVideoSource(0, listOf("same")) {
        override fun indexTitle(index: Int) = "same"

        override fun getVideoUrl() = "https://example.test/video"

        override fun getVideoTitle() = "same"

        override fun getCurrentPosition() = 0L

        override fun getMediaType() = MediaType.STREAM_LINK

        override fun getUniqueKey() = "same"

        override fun getStorageId() = 1
    }
}
