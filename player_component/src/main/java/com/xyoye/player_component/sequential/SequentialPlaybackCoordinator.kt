package com.xyoye.player_component.sequential

import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.data_component.enums.MediaType
import com.xyoye.player.BilibiliCompletionTrust
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
    private var playbackSnapshot: PlaybackSnapshot? = null

    fun onPlaybackStable() {
        isPlaybackStable = true
        considerQueueNextSource()
    }

    fun onPlaybackProgress(
        durationMs: Long,
        positionMs: Long,
        isLive: Boolean
    ) {
        playbackSnapshot = PlaybackSnapshot(durationMs, positionMs, isLive)
        considerQueueNextSource()
    }

    fun onPlaybackCompletionRequested(targetIndex: Int) {
        val active = sessionProvider()?.runtime ?: return
        if (isAutoPlayNextEnabled() && active.playback.queueIndex + 1 in active.playback.resolved.queue.indices) switchTo(targetIndex)
    }

    fun invalidateActiveSession() {
        requestedVersion = null
        isPlaybackStable = false
        playbackSnapshot = null
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
        if (active.playback.resolved.identity.mediaType == MediaType.BILIBILI_STORAGE) {
            val snapshot = playbackSnapshot ?: return
            if (snapshot.isLive || snapshot.durationMs <= 0) return
            if (snapshot.durationMs > BilibiliCompletionTrust.LONG_VIDEO_MIN_DURATION_MS &&
                snapshot.durationMs - snapshot.positionMs.coerceAtLeast(0) > BilibiliCompletionTrust.COMPLETION_TRUST_WINDOW_MS
            ) {
                return
            }
        }
        requestedVersion = active.version
        scope.launch { session.prepareNextNative(active.version) }
    }

    private data class PlaybackSnapshot(
        val durationMs: Long,
        val positionMs: Long,
        val isLive: Boolean
    )
}
