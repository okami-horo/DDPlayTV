package com.xyoye.player_component.sequential

import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.player_component.playback.session.ActivePlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Preload timing policy only. The session owns queue selection, preparation and all source lifetimes. */
internal class SequentialPlaybackCoordinator(
    private val scope: CoroutineScope,
    private val sessionProvider: () -> ActivePlaybackSession?,
    private val supportsSeamlessPlayback: () -> Boolean,
    private val switchTo: (Int) -> Unit,
    private val isAutoPlayNextEnabled: () -> Boolean
) {
    private var requestedVersion: PlaybackSessionVersion? = null
    private var isPlaybackStable = false

    fun onPlaybackStable() {
        isPlaybackStable = true
        considerQueueNextSource()
    }

    fun onPlaybackProgress() {
        considerQueueNextSource()
    }

    fun onPlaybackCompletionRequested(targetIndex: Int) {
        val active = sessionProvider()?.runtime ?: return
        if (isAutoPlayNextEnabled() && active.playback.queueIndex + 1 in active.playback.resolved.queue.indices) switchTo(targetIndex)
    }

    fun invalidateActiveSession() {
        requestedVersion = null
        isPlaybackStable = false
    }

    private fun considerQueueNextSource() {
        val session = sessionProvider() ?: return
        val active = session.runtime ?: return
        if (!isPlaybackStable ||
            !supportsSeamlessPlayback() ||
            !isAutoPlayNextEnabled() ||
            active.playback.queueIndex + 1 !in active.playback.resolved.queue.indices ||
            requestedVersion == active.version
        ) {
            return
        }
        requestedVersion = active.version
        scope.launch { session.prepareNextNative(active.version) }
    }
}
