package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.xyoye.player.kernel.subtitle.SubtitleFrameDriver
import java.lang.ref.WeakReference

@UnstableApi
class Media3SubtitleFrameDriver(
    private val player: ExoPlayer,
    callback: SubtitleFrameDriver.Callback
) : SubtitleFrameDriver,
    AnalyticsListener,
    VideoFrameMetadataListener {
    private var started = false
    private val callbackRef = WeakReference(callback)

    override fun start() {
        if (started) return
        started = true
        player.addAnalyticsListener(this)
        player.setVideoFrameMetadataListener(this)
    }

    override fun stop() {
        if (!started) return
        started = false
        player.removeAnalyticsListener(this)
        player.clearVideoFrameMetadataListener(this)
        callbackRef.clear()
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: android.media.MediaFormat?
    ) {
        val ptsMs = presentationTimeUs / 1_000L
        val vsyncId = releaseTimeNs / 1_000_000L
        callbackRef.get()?.onVideoFrame(ptsMs, vsyncId)
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        reason: Int
    ) {
        callbackRef.get()?.onTimelineJump(
            positionMs = player.contentPosition,
            playing = player.playWhenReady,
            reason = SubtitleFrameDriver.TimelineJumpReason.POSITION_DISCONTINUITY,
        )
    }

    override fun onSeekStarted(eventTime: AnalyticsListener.EventTime) {
        callbackRef.get()?.onTimelineJump(
            positionMs = player.contentPosition,
            playing = player.playWhenReady,
            reason = SubtitleFrameDriver.TimelineJumpReason.SEEK,
        )
    }

    override fun onTracksChanged(
        eventTime: AnalyticsListener.EventTime,
        tracks: Tracks
    ) {
        callbackRef.get()?.onTimelineJump(
            positionMs = player.contentPosition,
            playing = player.playWhenReady,
            reason = SubtitleFrameDriver.TimelineJumpReason.TRACKS_CHANGED,
        )
    }

    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int
    ) {
        if (state == Player.STATE_ENDED) {
            callbackRef.get()?.onPlaybackEnded()
        }
    }
}
