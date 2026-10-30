package com.xyoye.player.kernel.impl.media3

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
    private val handler = Handler(player.applicationLooper)
    private val callbackRef = WeakReference(callback)

    @Volatile private var started = false

    @Volatile private var generation = 0L

    @Volatile private var lastMetadataTimeMs = Long.MIN_VALUE

    @Volatile private var periodPositionUs = 0L

    override fun start() {
        if (started) return
        started = true
        generation++
        updatePeriodPosition()
        player.addAnalyticsListener(this)
        player.setVideoFrameMetadataListener(this)
        handler.post(positionTick)
    }

    override fun stop() {
        if (!started) return
        started = false
        generation++
        handler.removeCallbacksAndMessages(null)
        player.removeAnalyticsListener(this)
        player.clearVideoFrameMetadataListener(this)
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: android.media.MediaFormat?
    ) {
        if (!started) return
        val frameGeneration = generation
        // The marker travels with decoded output Format, so read-ahead cannot select a future period.
        val windowPositionUs = LibassTimelineRenderer.videoPeriodPositionUs(format) ?: periodPositionUs
        lastMetadataTimeMs = SystemClock.elapsedRealtime()
        handler.post {
            if (!started || frameGeneration != generation) return@post
            callbackRef.get()?.run {
                onVideoGeometry(format.width, format.height, format.pixelWidthHeightRatio, format.rotationDegrees)
                onVideoFrame(
                    presentationTimeUs + windowPositionUs,
                    releaseTimeNs.takeIf { it != C.TIME_UNSET && it > 0L }
                )
            }
        }
    }

    private val positionTick =
        object : Runnable {
            override fun run() {
                if (!started) return
                val now = SystemClock.elapsedRealtime()
                if (player.isPlaying && (lastMetadataTimeMs == Long.MIN_VALUE || now - lastMetadataTimeMs >= METADATA_TIMEOUT_MS)) {
                    player.videoFormat?.let { format ->
                        callbackRef.get()?.onVideoGeometry(
                            format.width,
                            format.height,
                            format.pixelWidthHeightRatio,
                            format.rotationDegrees
                        )
                    }
                    callbackRef.get()?.onVideoFrame(player.contentPosition * 1000L, null)
                }
                handler.postDelayed(this, POSITION_TICK_MS)
            }
        }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        reason: Int
    ) {
        timelineJump(SubtitleFrameDriver.TimelineJumpReason.POSITION_DISCONTINUITY)
    }

    override fun onTimelineChanged(
        eventTime: AnalyticsListener.EventTime,
        reason: Int
    ) {
        updatePeriodPosition()
    }

    override fun onSeekStarted(eventTime: AnalyticsListener.EventTime) {
        timelineJump(SubtitleFrameDriver.TimelineJumpReason.SEEK)
    }

    override fun onTracksChanged(
        eventTime: AnalyticsListener.EventTime,
        tracks: Tracks
    ) {
        timelineJump(SubtitleFrameDriver.TimelineJumpReason.TRACKS_CHANGED)
    }

    override fun onIsPlayingChanged(
        eventTime: AnalyticsListener.EventTime,
        isPlaying: Boolean
    ) {
        if (!isPlaying) callbackRef.get()?.onVideoFrame(player.contentPosition * 1000L, null)
    }

    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int
    ) {
        if (state == Player.STATE_ENDED) callbackRef.get()?.onPlaybackEnded()
    }

    private fun timelineJump(reason: SubtitleFrameDriver.TimelineJumpReason) {
        generation++
        lastMetadataTimeMs = Long.MIN_VALUE
        updatePeriodPosition()
        callbackRef.get()?.onTimelineJump(player.contentPosition, player.isPlaying, reason)
    }

    private fun updatePeriodPosition() {
        val timeline = player.currentTimeline
        periodPositionUs =
            if (timeline.isEmpty) {
                0L
            } else {
                timeline.getPeriod(player.currentPeriodIndex, Timeline.Period()).positionInWindowUs
            }
    }

    private companion object {
        const val POSITION_TICK_MS = 50L
        const val METADATA_TIMEOUT_MS = 250L
    }
}
