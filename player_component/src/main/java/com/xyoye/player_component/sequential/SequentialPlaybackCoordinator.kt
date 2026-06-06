package com.xyoye.player_component.sequential

import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.player.BilibiliCompletionTrust
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SequentialPlaybackCoordinator(
    private val scope: CoroutineScope,
    private val backendAdapterProvider: () -> SequentialPlaybackBackendAdapter,
    private val stateApplier: SequentialPlaybackStateApplier,
    private val fallbackSwitcher: SequentialPlaybackFallbackSwitcher,
    private val isAutoPlayNextEnabled: () -> Boolean,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SequentialPlaybackBackendAdapter.EventListener {
    private var activeAdapter: SequentialPlaybackBackendAdapter? = null
    private var activeSessionToken: SequentialPlaybackSessionToken? = null
    private var currentSource: BaseVideoSource? = null
    private var preparedTarget: PreparedTarget? = null
    private var prepareJob: Job? = null
    private var prepareRequestedGeneration: Long? = null
    private var generationCounter: Long = 0L
    private var queueItemCounter: Long = 0L
    private var isPlaybackStable: Boolean = false
    private var playbackSnapshot: PlaybackSnapshot? = null

    fun onSourceApplied(
        source: BaseVideoSource,
        playerType: PlayerType,
    ) {
        invalidateActiveSession()
        currentSource = source
        isPlaybackStable = false
        playbackSnapshot = null

        val adapter = backendAdapterProvider()
        adapter.setEventListener(this)
        activeAdapter = adapter

        val sessionToken = nextSessionToken(source, playerType)
        activeSessionToken = sessionToken
        adapter.beginSession(sessionToken, source)
    }

    fun onPlaybackStable() {
        isPlaybackStable = true
        considerQueueNextSource()
    }

    fun onPlaybackProgress(
        durationMs: Long,
        positionMs: Long,
        isLive: Boolean,
    ) {
        playbackSnapshot =
            PlaybackSnapshot(
                durationMs = durationMs,
                positionMs = positionMs,
                isLive = isLive,
            )
        considerQueueNextSource()
    }

    private fun considerQueueNextSource() {
        val adapter = activeAdapter ?: return
        val sessionToken = activeSessionToken ?: return
        val source = currentSource ?: return
        if (!isPlaybackStable) {
            return
        }
        if (!adapter.supportsSeamlessPlayback || !isAutoPlayNextEnabled() || !source.hasNextSource()) {
            return
        }
        if (!shouldQueueSeamlessly(source, playbackSnapshot)) {
            return
        }
        if (prepareRequestedGeneration == sessionToken.generation) {
            return
        }

        prepareRequestedGeneration = sessionToken.generation
        prepareJob?.cancel()
        prepareJob =
            scope.launch(workerDispatcher) {
                val targetIndex = source.getGroupIndex() + 1
                val nextSource = source.indexSource(targetIndex)
                handlePreparedSource(
                    sessionToken = sessionToken,
                    targetIndex = targetIndex,
                    nextSource = nextSource,
                )
            }
    }

    fun onPlaybackCompletionRequested(targetIndex: Int) {
        val source = currentSource ?: return
        if (!isAutoPlayNextEnabled() || !source.hasNextSource()) {
            return
        }
        fallbackSwitcher.switchTo(targetIndex)
    }

    fun invalidateActiveSession() {
        prepareJob?.cancel()
        prepareJob = null
        prepareRequestedGeneration = null
        preparedTarget = null
        isPlaybackStable = false
        playbackSnapshot = null

        activeSessionToken?.let { token ->
            activeAdapter?.invalidateSession(token)
        }
        activeAdapter?.setEventListener(null)
        activeAdapter = null
        activeSessionToken = null
    }

    override fun onTransitionToQueuedItem(
        sessionToken: SequentialPlaybackSessionToken,
        queueItemId: SequentialPlaybackQueueItemId,
    ) {
        val activeToken = activeSessionToken ?: return
        if (activeToken != sessionToken) {
            return
        }

        val prepared = preparedTarget ?: return
        if (prepared.sessionToken != sessionToken || prepared.queueItemId != queueItemId) {
            return
        }

        val previousSource = currentSource ?: return
        preparedTarget = null
        prepareRequestedGeneration = null
        currentSource = prepared.source

        stateApplier.apply(
            SequentialPlaybackTransition(
                previousSource = previousSource,
                nextSource = prepared.source,
                sessionToken = sessionToken,
                queueItemId = queueItemId,
            ),
        )

        val nextToken = nextSessionToken(prepared.source, sessionToken.playerType)
        activeSessionToken = nextToken
        activeAdapter?.beginSession(nextToken, prepared.source)
    }

    private suspend fun handlePreparedSource(
        sessionToken: SequentialPlaybackSessionToken,
        targetIndex: Int,
        nextSource: BaseVideoSource?,
    ) {
        val activeToken = activeSessionToken ?: return
        if (activeToken != sessionToken) {
            return
        }

        val adapter = activeAdapter ?: return
        if (nextSource == null) {
            preparedTarget = null
            return
        }

        val queueItemId = SequentialPlaybackQueueItemId(++queueItemCounter)
        val result =
            adapter.queueNextSource(
                sessionToken = sessionToken,
                queueItemId = queueItemId,
                nextSource = nextSource,
            )

        preparedTarget =
            if (result.isSuccess) {
                PreparedTarget(
                    sessionToken = sessionToken,
                    queueItemId = queueItemId,
                    source = nextSource,
                    index = targetIndex,
                )
            } else {
                null
            }
    }

    private fun nextSessionToken(
        source: BaseVideoSource,
        playerType: PlayerType,
    ): SequentialPlaybackSessionToken =
        SequentialPlaybackSessionToken(
            generation = ++generationCounter,
            sourceUniqueKey = source.getUniqueKey(),
            playerType = playerType,
        )

    private fun shouldQueueSeamlessly(
        source: BaseVideoSource,
        snapshot: PlaybackSnapshot?,
    ): Boolean {
        if (source.getMediaType() != MediaType.BILIBILI_STORAGE) {
            return true
        }

        val currentSnapshot = snapshot ?: return false
        if (currentSnapshot.isLive) {
            return false
        }

        val durationMs = currentSnapshot.durationMs.coerceAtLeast(0L)
        if (durationMs <= 0L) {
            return false
        }
        if (durationMs <= BilibiliCompletionTrust.LONG_VIDEO_MIN_DURATION_MS) {
            return true
        }

        val remainingMs =
            (durationMs - currentSnapshot.positionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        return remainingMs <= BilibiliCompletionTrust.COMPLETION_TRUST_WINDOW_MS
    }

    private data class PreparedTarget(
        val sessionToken: SequentialPlaybackSessionToken,
        val queueItemId: SequentialPlaybackQueueItemId,
        val source: BaseVideoSource,
        val index: Int,
    )

    private data class PlaybackSnapshot(
        val durationMs: Long,
        val positionMs: Long,
        val isLive: Boolean,
    )
}
