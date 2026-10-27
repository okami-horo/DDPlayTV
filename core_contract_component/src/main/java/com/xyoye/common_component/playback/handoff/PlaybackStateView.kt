package com.xyoye.common_component.playback.handoff

import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.media3.entity.Media3BackgroundMode
import com.xyoye.data_component.media3.entity.Media3Capability
import com.xyoye.data_component.media3.entity.Media3PlaybackState
import java.util.Collections

data class PlaybackSessionVersion(
    val sessionId: String,
    val sourceRevision: Long
)

/** One publication contains the actual identity and supported capabilities from the same revision. */
class PlaybackStateView(
    val version: PlaybackSessionVersion,
    val source: PlaybackSourceView,
    val state: Media3PlaybackState,
    capabilities: Set<Media3Capability>,
    backgroundModes: Set<Media3BackgroundMode>,
    val lastPersistedSourceRevision: Long?,
    val recoverySaveFailed: Boolean,
    val playerType: PlayerType
) {
    val capabilities: Set<Media3Capability> = Collections.unmodifiableSet(capabilities.toSet())
    val backgroundModes: Set<Media3BackgroundMode> = Collections.unmodifiableSet(backgroundModes.toSet())
}

data class PlaybackSourceView(
    val mediaId: String,
    val uniqueKey: String,
    val mediaType: MediaType,
    val displayTitle: String,
    val libraryId: Int?
) {
    override fun toString(): String = "PlaybackSourceView(mediaType=$mediaType)"
}

data class PlaybackCommand(
    val expected: PlaybackSessionVersion,
    val action: PlaybackAction
)

sealed interface PlaybackAction {
    object Play : PlaybackAction

    object Pause : PlaybackAction

    object Exit : PlaybackAction

    object EnterBackground : PlaybackAction

    object EnterPictureInPicture : PlaybackAction

    data class Seek(
        val positionMs: Long
    ) : PlaybackAction

    data class SetSpeed(
        val speed: Float
    ) : PlaybackAction

    data class SelectQueueItem(
        val uniqueKey: String
    ) : PlaybackAction {
        override fun toString(): String = "SelectQueueItem"
    }
}
