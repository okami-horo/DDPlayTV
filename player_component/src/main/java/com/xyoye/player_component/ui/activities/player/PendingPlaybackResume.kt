package com.xyoye.player_component.ui.activities.player

import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.player_component.playback.session.PlaybackPositionConstraints

/** The backend's explicit progress wins over a Source's older history, including an explicit zero. */
internal class PendingPlaybackResume {
    private var source: BaseVideoSource? = null
    private var positionMs = 0L

    fun prepare(
        source: BaseVideoSource,
        positionMs: Long
    ) {
        this.source = source
        this.positionMs = positionMs
    }

    fun take(
        source: BaseVideoSource,
        constraints: PlaybackPositionConstraints
    ): Long? {
        if (this.source !== source) return null
        this.source = null
        return constraints.normalize(positionMs)
    }

    fun clear() {
        source = null
    }
}
