package com.xyoye.player_component.sequential

import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.player.BilibiliCompletionTrust
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SequentialPlaybackCoordinatorTest {
    @Test
    fun queuesNextSourceWhenStablePlaybackHasNextItem() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val adapter = RecordingBackendAdapter(supportsSeamlessPlayback = true)
            val stateApplier = RecordingStateApplier()
            val fallbackSwitcher = RecordingFallbackSwitcher()
            val sources = createSources("episode-1", "episode-2")
            val coordinator =
                SequentialPlaybackCoordinator(
                    scope = this,
                    backendAdapterProvider = { adapter },
                    stateApplier = stateApplier,
                    fallbackSwitcher = fallbackSwitcher,
                    isAutoPlayNextEnabled = { true },
                    workerDispatcher = dispatcher,
                )

            coordinator.onSourceApplied(sources.first(), PlayerType.TYPE_EXO_PLAYER)
            coordinator.onPlaybackStable()
            advanceUntilIdle()

            assertEquals(1, adapter.beginSessions.size)
            assertEquals(1, adapter.queueRequests.size)
            assertEquals("episode-2", adapter.queueRequests.single().nextSource.getUniqueKey())
            assertTrue(stateApplier.transitions.isEmpty())
            assertTrue(fallbackSwitcher.indices.isEmpty())
        }

    @Test
    fun doesNotQueueWhenCurrentSourceHasNoNextItem() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val adapter = RecordingBackendAdapter(supportsSeamlessPlayback = true)
            val coordinator =
                SequentialPlaybackCoordinator(
                    scope = this,
                    backendAdapterProvider = { adapter },
                    stateApplier = RecordingStateApplier(),
                    fallbackSwitcher = RecordingFallbackSwitcher(),
                    isAutoPlayNextEnabled = { true },
                    workerDispatcher = dispatcher,
                )
            val sources = createSources("final-episode")

            coordinator.onSourceApplied(sources.first(), PlayerType.TYPE_EXO_PLAYER)
            coordinator.onPlaybackStable()
            advanceUntilIdle()

            assertTrue(adapter.queueRequests.isEmpty())
        }

    @Test
    fun queuesShortBilibiliSourceAfterProgressConfirmsShortDuration() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val adapter = RecordingBackendAdapter(supportsSeamlessPlayback = true)
            val coordinator =
                SequentialPlaybackCoordinator(
                    scope = this,
                    backendAdapterProvider = { adapter },
                    stateApplier = RecordingStateApplier(),
                    fallbackSwitcher = RecordingFallbackSwitcher(),
                    isAutoPlayNextEnabled = { true },
                    workerDispatcher = dispatcher,
                )
            val sources = createSources(MediaType.BILIBILI_STORAGE, "episode-1", "episode-2")

            coordinator.onSourceApplied(sources.first(), PlayerType.TYPE_EXO_PLAYER)
            coordinator.onPlaybackStable()
            coordinator.onPlaybackProgress(
                durationMs = 5 * 60_000L,
                positionMs = 30_000L,
                isLive = false,
            )
            advanceUntilIdle()

            assertEquals(1, adapter.queueRequests.size)
        }

    @Test
    fun queuesBilibiliSourceAtTenMinuteThreshold() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val adapter = RecordingBackendAdapter(supportsSeamlessPlayback = true)
            val coordinator =
                SequentialPlaybackCoordinator(
                    scope = this,
                    backendAdapterProvider = { adapter },
                    stateApplier = RecordingStateApplier(),
                    fallbackSwitcher = RecordingFallbackSwitcher(),
                    isAutoPlayNextEnabled = { true },
                    workerDispatcher = dispatcher,
                )
            val sources = createSources(MediaType.BILIBILI_STORAGE, "episode-1", "episode-2")

            coordinator.onSourceApplied(sources.first(), PlayerType.TYPE_EXO_PLAYER)
            coordinator.onPlaybackStable()
            coordinator.onPlaybackProgress(
                durationMs = BilibiliCompletionTrust.LONG_VIDEO_MIN_DURATION_MS,
                positionMs = 30_000L,
                isLive = false,
            )
            advanceUntilIdle()

            assertEquals(1, adapter.queueRequests.size)
        }

    @Test
    fun waitsToQueueLongBilibiliSourceUntilTailWindow() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val adapter = RecordingBackendAdapter(supportsSeamlessPlayback = true)
            val coordinator =
                SequentialPlaybackCoordinator(
                    scope = this,
                    backendAdapterProvider = { adapter },
                    stateApplier = RecordingStateApplier(),
                    fallbackSwitcher = RecordingFallbackSwitcher(),
                    isAutoPlayNextEnabled = { true },
                    workerDispatcher = dispatcher,
                )
            val sources = createSources(MediaType.BILIBILI_STORAGE, "episode-1", "episode-2")

            coordinator.onSourceApplied(sources.first(), PlayerType.TYPE_EXO_PLAYER)
            coordinator.onPlaybackStable()
            coordinator.onPlaybackProgress(
                durationMs = 20 * 60_000L,
                positionMs = 5 * 60_000L,
                isLive = false,
            )
            advanceUntilIdle()

            assertTrue(adapter.queueRequests.isEmpty())

            coordinator.onPlaybackProgress(
                durationMs = 20 * 60_000L,
                positionMs = 20 * 60_000L - BilibiliCompletionTrust.COMPLETION_TRUST_WINDOW_MS + 1_000L,
                isLive = false,
            )
            advanceUntilIdle()

            assertEquals(1, adapter.queueRequests.size)
        }

    @Test
    fun doesNotQueueLiveBilibiliSource() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val adapter = RecordingBackendAdapter(supportsSeamlessPlayback = true)
            val coordinator =
                SequentialPlaybackCoordinator(
                    scope = this,
                    backendAdapterProvider = { adapter },
                    stateApplier = RecordingStateApplier(),
                    fallbackSwitcher = RecordingFallbackSwitcher(),
                    isAutoPlayNextEnabled = { true },
                    workerDispatcher = dispatcher,
                )
            val sources = createSources(MediaType.BILIBILI_STORAGE, "episode-1", "episode-2")

            coordinator.onSourceApplied(sources.first(), PlayerType.TYPE_EXO_PLAYER)
            coordinator.onPlaybackStable()
            coordinator.onPlaybackProgress(
                durationMs = -1L,
                positionMs = 5 * 60_000L,
                isLive = true,
            )
            advanceUntilIdle()

            assertTrue(adapter.queueRequests.isEmpty())
        }

    @Test
    fun ignoresTransitionsFromExpiredSessionTokens() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val adapter = RecordingBackendAdapter(supportsSeamlessPlayback = true)
            val stateApplier = RecordingStateApplier()
            val sources = createSources("episode-1", "episode-2")
            val coordinator =
                SequentialPlaybackCoordinator(
                    scope = this,
                    backendAdapterProvider = { adapter },
                    stateApplier = stateApplier,
                    fallbackSwitcher = RecordingFallbackSwitcher(),
                    isAutoPlayNextEnabled = { true },
                    workerDispatcher = dispatcher,
                )

            coordinator.onSourceApplied(sources.first(), PlayerType.TYPE_EXO_PLAYER)
            coordinator.onPlaybackStable()
            advanceUntilIdle()
            val staleRequest = adapter.queueRequests.single()

            coordinator.onSourceApplied(sources.last(), PlayerType.TYPE_EXO_PLAYER)
            adapter.emitTransition(staleRequest.sessionToken, staleRequest.queueItemId)

            assertTrue(stateApplier.transitions.isEmpty())
        }

    @Test
    fun ignoresTransitionsWithUnexpectedQueueItemIds() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val adapter = RecordingBackendAdapter(supportsSeamlessPlayback = true)
            val stateApplier = RecordingStateApplier()
            val sources = createSources("episode-1", "episode-2")
            val coordinator =
                SequentialPlaybackCoordinator(
                    scope = this,
                    backendAdapterProvider = { adapter },
                    stateApplier = stateApplier,
                    fallbackSwitcher = RecordingFallbackSwitcher(),
                    isAutoPlayNextEnabled = { true },
                    workerDispatcher = dispatcher,
                )

            coordinator.onSourceApplied(sources.first(), PlayerType.TYPE_EXO_PLAYER)
            coordinator.onPlaybackStable()
            advanceUntilIdle()
            val request = adapter.queueRequests.single()

            adapter.emitTransition(
                request.sessionToken,
                SequentialPlaybackQueueItemId(request.queueItemId.value + 1),
            )

            assertTrue(stateApplier.transitions.isEmpty())
        }

    @Test
    fun appliesTransitionAndStartsNextSessionWhenQueuedItemBecomesCurrent() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val adapter = RecordingBackendAdapter(supportsSeamlessPlayback = true)
            val stateApplier = RecordingStateApplier()
            val sources = createSources("episode-1", "episode-2", "episode-3")
            val coordinator =
                SequentialPlaybackCoordinator(
                    scope = this,
                    backendAdapterProvider = { adapter },
                    stateApplier = stateApplier,
                    fallbackSwitcher = RecordingFallbackSwitcher(),
                    isAutoPlayNextEnabled = { true },
                    workerDispatcher = dispatcher,
                )

            coordinator.onSourceApplied(sources.first(), PlayerType.TYPE_EXO_PLAYER)
            coordinator.onPlaybackStable()
            advanceUntilIdle()
            val request = adapter.queueRequests.single()

            adapter.emitTransition(request.sessionToken, request.queueItemId)

            assertEquals(1, stateApplier.transitions.size)
            assertEquals("episode-1", stateApplier.transitions.single().previousSource.getUniqueKey())
            assertEquals("episode-2", stateApplier.transitions.single().nextSource.getUniqueKey())
            assertEquals(2, adapter.beginSessions.size)
            assertEquals("episode-2", adapter.beginSessions.last().source.getUniqueKey())
        }

    @Test
    fun fallsBackToLegacySwitchWhenQueuePreparationFails() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val adapter =
                RecordingBackendAdapter(
                    supportsSeamlessPlayback = true,
                    failQueueRequests = true,
                )
            val stateApplier = RecordingStateApplier()
            val fallbackSwitcher = RecordingFallbackSwitcher()
            val coordinator =
                SequentialPlaybackCoordinator(
                    scope = this,
                    backendAdapterProvider = { adapter },
                    stateApplier = stateApplier,
                    fallbackSwitcher = fallbackSwitcher,
                    isAutoPlayNextEnabled = { true },
                    workerDispatcher = dispatcher,
                )
            val sources = createSources("episode-1", "episode-2")

            coordinator.onSourceApplied(sources.first(), PlayerType.TYPE_EXO_PLAYER)
            coordinator.onPlaybackStable()
            advanceUntilIdle()
            coordinator.onPlaybackCompletionRequested(targetIndex = 1)

            assertEquals(1, adapter.queueRequests.size)
            assertTrue(stateApplier.transitions.isEmpty())
            assertEquals(listOf(1), fallbackSwitcher.indices)
        }

    @Test
    fun fallsBackToLegacySwitchWhenCompletionArrivesWithoutSeamlessQueue() =
        runTest {
            val fallbackSwitcher = RecordingFallbackSwitcher()
            val coordinator =
                SequentialPlaybackCoordinator(
                    scope = this,
                    backendAdapterProvider = { FallbackSequentialPlaybackAdapter() },
                    stateApplier = RecordingStateApplier(),
                    fallbackSwitcher = fallbackSwitcher,
                    isAutoPlayNextEnabled = { true },
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                )
            val sources = createSources("episode-1", "episode-2")

            coordinator.onSourceApplied(sources.first(), PlayerType.TYPE_VLC_PLAYER)
            coordinator.onPlaybackCompletionRequested(targetIndex = 1)

            assertEquals(listOf(1), fallbackSwitcher.indices)
        }

    private class RecordingBackendAdapter(
        override val supportsSeamlessPlayback: Boolean,
        private val failQueueRequests: Boolean = false,
    ) : SequentialPlaybackBackendAdapter {
        var listener: SequentialPlaybackBackendAdapter.EventListener? = null
        val beginSessions = mutableListOf<BeginSessionRecord>()
        val queueRequests = mutableListOf<QueueRequest>()
        val invalidatedSessions = mutableListOf<SequentialPlaybackSessionToken>()

        override fun setEventListener(listener: SequentialPlaybackBackendAdapter.EventListener?) {
            this.listener = listener
        }

        override fun beginSession(
            sessionToken: SequentialPlaybackSessionToken,
            currentSource: BaseVideoSource,
        ) {
            beginSessions += BeginSessionRecord(sessionToken, currentSource)
        }

        override fun queueNextSource(
            sessionToken: SequentialPlaybackSessionToken,
            queueItemId: SequentialPlaybackQueueItemId,
            nextSource: BaseVideoSource,
        ): Result<Unit> {
            queueRequests += QueueRequest(sessionToken, queueItemId, nextSource)
            return if (supportsSeamlessPlayback && !failQueueRequests) {
                Result.success(Unit)
            } else {
                Result.failure(UnsupportedOperationException("Queueing disabled"))
            }
        }

        override fun invalidateSession(sessionToken: SequentialPlaybackSessionToken) {
            invalidatedSessions += sessionToken
        }

        fun emitTransition(
            sessionToken: SequentialPlaybackSessionToken,
            queueItemId: SequentialPlaybackQueueItemId,
        ) {
            listener?.onTransitionToQueuedItem(sessionToken, queueItemId)
        }
    }

    private class RecordingStateApplier : SequentialPlaybackStateApplier {
        val transitions = mutableListOf<SequentialPlaybackTransition>()

        override fun apply(transition: SequentialPlaybackTransition) {
            transitions += transition
        }
    }

    private class RecordingFallbackSwitcher : SequentialPlaybackFallbackSwitcher {
        val indices = mutableListOf<Int>()

        override fun switchTo(index: Int) {
            indices += index
        }
    }

    private data class BeginSessionRecord(
        val sessionToken: SequentialPlaybackSessionToken,
        val source: BaseVideoSource,
    )

    private data class QueueRequest(
        val sessionToken: SequentialPlaybackSessionToken,
        val queueItemId: SequentialPlaybackQueueItemId,
        val nextSource: BaseVideoSource,
    )

    private class FakeVideoSource(
        private val ids: List<String>,
        private val index: Int,
        private val mediaType: MediaType,
        private var currentPositionMs: Long = 0L,
    ) : BaseVideoSource(index, ids) {
        lateinit var resolver: (Int) -> FakeVideoSource?

        override fun indexTitle(index: Int): String = ids[index]

        override suspend fun indexSource(index: Int): BaseVideoSource? = resolver(index)

        override fun getVideoUrl(): String = "file:///${ids[index]}.mp4"

        override fun getVideoTitle(): String = ids[index]

        override fun getCurrentPosition(): Long = currentPositionMs

        override fun getMediaType(): MediaType = mediaType

        override fun getUniqueKey(): String = ids[index]

        override fun getStorageId(): Int = 1
    }

    private fun createSources(vararg ids: String): List<FakeVideoSource> =
        createSources(MediaType.LOCAL_STORAGE, *ids)

    private fun createSources(
        mediaType: MediaType,
        vararg ids: String
    ): List<FakeVideoSource> {
        lateinit var sources: List<FakeVideoSource>
        sources = ids.mapIndexed { index, _ -> FakeVideoSource(ids.toList(), index, mediaType) }
        sources.forEach { source ->
            source.resolver = { targetIndex -> sources.getOrNull(targetIndex) }
        }
        return sources
    }
}
