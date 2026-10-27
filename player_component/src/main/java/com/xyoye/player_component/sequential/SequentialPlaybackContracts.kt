package com.xyoye.player_component.sequential

import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.enums.PlayerType

data class SequentialPlaybackSessionToken(
    val version: PlaybackSessionVersion,
    val sourceUniqueKey: String,
    val playerType: PlayerType
)

@JvmInline
value class SequentialPlaybackQueueItemId(
    val value: String
)

enum class SequentialQueueRetirement {
    DETACHED,
    CURRENT,
    STILL_ATTACHED
}

interface SequentialPlaybackBackendAdapter {
    val supportsSeamlessPlayback: Boolean

    fun setEventListener(listener: EventListener?)

    fun beginSession(
        sessionToken: SequentialPlaybackSessionToken,
        currentSource: BaseVideoSource
    )

    fun queueNextSource(
        sessionToken: SequentialPlaybackSessionToken,
        queueItemId: SequentialPlaybackQueueItemId,
        nextSource: BaseVideoSource
    ): Result<Unit>

    fun invalidateSession(sessionToken: SequentialPlaybackSessionToken): SequentialQueueRetirement

    interface EventListener {
        fun onTransitionToQueuedItem(
            sessionToken: SequentialPlaybackSessionToken,
            queueItemId: SequentialPlaybackQueueItemId
        )
    }
}

class FallbackSequentialPlaybackAdapter : SequentialPlaybackBackendAdapter {
    override val supportsSeamlessPlayback: Boolean = false

    override fun setEventListener(listener: SequentialPlaybackBackendAdapter.EventListener?) = Unit

    override fun beginSession(
        sessionToken: SequentialPlaybackSessionToken,
        currentSource: BaseVideoSource
    ) = Unit

    override fun queueNextSource(
        sessionToken: SequentialPlaybackSessionToken,
        queueItemId: SequentialPlaybackQueueItemId,
        nextSource: BaseVideoSource
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Fallback adapter does not support queueing"))

    override fun invalidateSession(sessionToken: SequentialPlaybackSessionToken) = SequentialQueueRetirement.DETACHED
}
