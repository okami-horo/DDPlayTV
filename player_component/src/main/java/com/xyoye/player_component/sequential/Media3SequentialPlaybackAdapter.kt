package com.xyoye.player_component.sequential

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.impl.media3.Media3MediaSourceHelper

@UnstableApi
class Media3SequentialPlaybackAdapter private constructor(
    private val queuePlayerProvider: () -> Media3QueuePlayer?
) : SequentialPlaybackBackendAdapter {
    override val supportsSeamlessPlayback: Boolean = true

    private var eventListener: SequentialPlaybackBackendAdapter.EventListener? = null
    private var activeSessionToken: SequentialPlaybackSessionToken? = null
    private var attachedQueuePlayer: Media3QueuePlayer? = null
    private var queuedItem: QueuedMediaItem? = null
    private var activeSource: BaseVideoSource? = null
    private val borrowedSources = mutableSetOf<BaseVideoSource>()

    fun currentSource(): BaseVideoSource? =
        queuedItem?.takeIf { attachedQueuePlayer?.currentMediaId() == it.mediaId }?.source ?: activeSource

    fun isAttached(source: BaseVideoSource): Boolean = source in borrowedSources

    /** Call only after the underlying kernel was released successfully. */
    fun onBackendDetached() {
        detachPlayerListener()
        borrowedSources.clear()
        activeSource = null
    }

    override fun setEventListener(listener: SequentialPlaybackBackendAdapter.EventListener?) {
        eventListener = listener
        if (listener == null) {
            detachPlayerListener()
        }
    }

    override fun beginSession(
        sessionToken: SequentialPlaybackSessionToken,
        currentSource: BaseVideoSource
    ) {
        activeSessionToken = sessionToken
        val player = attachPlayerListener() ?: return
        if (runCatching { trimQueueToCurrent(player) }.isSuccess) borrowedSources.clear()
        borrowedSources.add(currentSource)
        activeSource = currentSource
        queuedItem = null
    }

    override fun queueNextSource(
        sessionToken: SequentialPlaybackSessionToken,
        queueItemId: SequentialPlaybackQueueItemId,
        nextSource: BaseVideoSource
    ): Result<Unit> =
        runCatching {
            val player = attachPlayerListener() ?: error("Media3 player is unavailable")
            val mediaId = buildMediaId(sessionToken, queueItemId)
            trimQueueToCurrent(player)
            queuedItem =
                QueuedMediaItem(
                    sessionToken = sessionToken,
                    queueItemId = queueItemId,
                    mediaId = mediaId,
                    source = nextSource
                )
            borrowedSources.add(nextSource)
            player.enqueueNextSource(nextSource, mediaId)
        }

    override fun invalidateSession(sessionToken: SequentialPlaybackSessionToken): SequentialQueueRetirement {
        if (activeSessionToken != sessionToken) return SequentialQueueRetirement.STILL_ATTACHED
        val player = attachedQueuePlayer
        val pending = queuedItem
        // A transition may already be visible on Player before its listener is delivered.
        val becameCurrent = pending != null && player?.currentMediaId() == pending.mediaId
        val outcome =
            if (player == null) {
                if (pending == null) SequentialQueueRetirement.DETACHED else SequentialQueueRetirement.STILL_ATTACHED
            } else {
                val removed = runCatching { trimQueueToCurrent(player) }.isSuccess
                if (removed && becameCurrent) borrowedSources.retainAll(setOf(pending!!.source))
                when {
                    becameCurrent -> SequentialQueueRetirement.CURRENT
                    removed -> SequentialQueueRetirement.DETACHED
                    else -> SequentialQueueRetirement.STILL_ATTACHED
                }
            }
        if (outcome == SequentialQueueRetirement.DETACHED) {
            pending?.let { borrowedSources.remove(it.source) }
            queuedItem = null
            activeSessionToken = null
        }
        return outcome
    }

    private fun attachPlayerListener(): Media3QueuePlayer? {
        val queuePlayer = queuePlayerProvider() ?: return null
        if (attachedQueuePlayer?.identity === queuePlayer.identity) {
            return attachedQueuePlayer
        }

        detachPlayerListener(clearSessionState = false)
        attachedQueuePlayer = queuePlayer
        queuePlayer.setTransitionListener { mediaId ->
            handleMediaItemTransition(mediaId)
        }
        return queuePlayer
    }

    private fun detachPlayerListener(clearSessionState: Boolean = true) {
        attachedQueuePlayer?.setTransitionListener(null)
        attachedQueuePlayer = null
        queuedItem = null
        if (clearSessionState) {
            activeSessionToken = null
        }
    }

    private fun handleMediaItemTransition(mediaId: String?) {
        val activeToken = activeSessionToken ?: return
        val queued = queuedItem

        if (queued != null && mediaId == queued.mediaId && activeToken == queued.sessionToken) {
            if (attachedQueuePlayer?.let { runCatching { trimQueueToCurrent(it) }.isSuccess } == true) {
                borrowedSources.retainAll(setOf(queued.source))
            }
            if (queued.notified) return
            queued.notified = true
            eventListener?.onTransitionToQueuedItem(
                queued.sessionToken,
                queued.queueItemId
            )
            return
        }
    }

    private fun trimQueueToCurrent(queuePlayer: Media3QueuePlayer) {
        if (queuePlayer.mediaItemCount() <= 0) {
            return
        }

        val currentIndex = queuePlayer.currentMediaItemIndex().coerceAtLeast(0)
        if (currentIndex > 0) {
            queuePlayer.removeMediaItems(0, currentIndex)
        }

        val mediaItemCount = queuePlayer.mediaItemCount()
        if (mediaItemCount > 1) {
            queuePlayer.removeMediaItems(1, mediaItemCount)
        }
    }

    private fun buildMediaId(
        sessionToken: SequentialPlaybackSessionToken,
        queueItemId: SequentialPlaybackQueueItemId
    ): String = "sequential-${sessionToken.version.sessionId}-${sessionToken.version.sourceRevision}-${queueItemId.value}"

    private data class QueuedMediaItem(
        val sessionToken: SequentialPlaybackSessionToken,
        val queueItemId: SequentialPlaybackQueueItemId,
        val mediaId: String,
        val source: BaseVideoSource,
        var notified: Boolean = false
    )

    internal companion object {
        fun create(playerProvider: () -> ExoPlayer?): Media3SequentialPlaybackAdapter =
            Media3SequentialPlaybackAdapter(
                queuePlayerProvider = {
                    playerProvider()?.let { exoPlayer ->
                        ExoPlayerQueuePlayer(exoPlayer)
                    }
                }
            )

        fun createForTests(queuePlayerProvider: () -> Media3QueuePlayer?): Media3SequentialPlaybackAdapter =
            Media3SequentialPlaybackAdapter(queuePlayerProvider)
    }

    internal interface Media3QueuePlayer {
        val identity: Any

        fun setTransitionListener(listener: ((String?) -> Unit)?)

        fun currentMediaId(): String?

        fun currentMediaItemIndex(): Int

        fun mediaItemCount(): Int

        fun removeMediaItems(
            fromIndex: Int,
            toIndex: Int
        )

        fun enqueueNextSource(
            nextSource: BaseVideoSource,
            mediaId: String
        )
    }

    private class ExoPlayerQueuePlayer(
        private val player: ExoPlayer
    ) : Media3QueuePlayer {
        private var transitionListener: ((String?) -> Unit)? = null
        private val playerListener =
            object : Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int
                ) {
                    transitionListener?.invoke(mediaItem?.mediaId)
                }
            }

        override val identity: Any
            get() = player

        override fun setTransitionListener(listener: ((String?) -> Unit)?) {
            transitionListener = listener
            player.removeListener(playerListener)
            if (listener != null) {
                player.addListener(playerListener)
            }
        }

        override fun currentMediaId(): String? = player.currentMediaItem?.mediaId

        override fun currentMediaItemIndex(): Int = player.currentMediaItemIndex

        override fun mediaItemCount(): Int = player.mediaItemCount

        override fun removeMediaItems(
            fromIndex: Int,
            toIndex: Int
        ) {
            player.removeMediaItems(fromIndex, toIndex)
        }

        override fun enqueueNextSource(
            nextSource: BaseVideoSource,
            mediaId: String
        ) {
            val mediaSource =
                Media3MediaSourceHelper.getMediaSource(
                    uri = nextSource.getVideoUrl(),
                    headers = nextSource.getHttpHeader(),
                    parseSubtitlesDuringExtraction = PlayerInitializer.Subtitle.backend != SubtitleRendererBackend.LIBASS,
                    mediaId = mediaId
                )
            player.addMediaSource(mediaSource)
        }
    }
}
