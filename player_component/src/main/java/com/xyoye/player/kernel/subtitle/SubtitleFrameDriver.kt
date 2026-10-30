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
     * Frame time is media window time in microseconds; targets use System.nanoTime().
     */
    interface Callback {
        fun onVideoFrame(
            contentTimeUs: Long,
            targetPresentTimeNs: Long?
        )

        fun onVideoGeometry(
            width: Int,
            height: Int,
            pixelRatio: Float,
            rotationDegrees: Int
        ) = Unit

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
