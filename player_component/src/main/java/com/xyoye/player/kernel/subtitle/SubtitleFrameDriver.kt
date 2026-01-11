package com.xyoye.player.kernel.subtitle

/**
 * Drives subtitle rendering ticks from a specific player kernel.
 *
 * Implementations should be cheap to start/stop because a subtitle session is bound to the
 * player view lifecycle.
 */
interface SubtitleFrameDriver {
    fun start()

    fun stop()

    /**
     * Callback invoked by frame drivers.
     *
     * All timestamps are in the media timeline (milliseconds) unless otherwise specified.
     */
    interface Callback {
        fun onVideoFrame(
            videoPtsMs: Long,
            vsyncId: Long
        )

        fun onTimelineJump(
            positionMs: Long,
            playing: Boolean,
            reason: TimelineJumpReason
        )

        fun onPlaybackEnded()
    }

    enum class TimelineJumpReason {
        POSITION_DISCONTINUITY,
        SEEK,
        TRACKS_CHANGED
    }
}
