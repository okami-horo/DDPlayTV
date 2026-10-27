package com.xyoye.player_component.ui.activities.player

import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure

/** Presentation-only preparation/error state; source ownership remains in ActivePlaybackSession. */
internal data class PlaybackScreenState(
    val preparing: Boolean = false,
    val hasPlayback: Boolean = false,
    val failure: PlaybackPreparationFailure? = null
)
