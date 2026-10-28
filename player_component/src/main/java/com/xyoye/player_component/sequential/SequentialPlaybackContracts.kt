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
