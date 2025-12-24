package com.xyoye.player_component.media3.render

import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.player_component.subtitle.gpu.AssGpuRenderer
import com.xyoye.player_component.subtitle.gpu.SubtitleFrameCleaner

/**
 * Aligns GPU subtitle rendering with the player timeline using Media3 callbacks.
 */
@UnstableApi
class SubtitleRenderScheduler(
    private val player: ExoPlayer,
    private val renderer: AssGpuRenderer,
    private val frameCleaner: SubtitleFrameCleaner = renderer.frameCleaner,
    private val shouldRender: () -> Boolean = { true }
) : AnalyticsListener,
    VideoFrameMetadataListener {
    private var started = false

    fun start() {
        if (started) return
        started = true
        player.addAnalyticsListener(this)
        player.setVideoFrameMetadataListener(this)
    }

    fun stop() {
        if (!started) return
        started = false
        player.removeAnalyticsListener(this)
        player.clearVideoFrameMetadataListener(this)
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: android.media.MediaFormat?
    ) {
        if (!shouldRender()) return
        val ptsMs =
            ((presentationTimeUs / 1_000L) + SubtitlePreferenceUpdater.currentOffset()).coerceAtLeast(0L)
        val vsyncId = releaseTimeNs / 1_000_000L
        renderer.renderFrame(ptsMs, vsyncId)
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        reason: Int
    ) {
        frameCleaner.onSeek()
        if (!player.playWhenReady) {
            renderOnce(player.contentPosition)
        }
    }

    override fun onSeekStarted(eventTime: AnalyticsListener.EventTime) {
        frameCleaner.onSeek()
        if (!player.playWhenReady) {
            renderOnce(player.contentPosition)
        }
    }

    override fun onTracksChanged(
        eventTime: AnalyticsListener.EventTime,
        tracks: Tracks
    ) {
        frameCleaner.onTrackChanged()
        if (!player.playWhenReady) {
            renderOnce(player.contentPosition)
        }
    }

    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int
    ) {
        if (state == Player.STATE_ENDED) {
            frameCleaner.onTrackChanged()
        }
    }

    fun updateOffset(newOffsetMs: Long) {
        SubtitlePreferenceUpdater.persistOffset(newOffsetMs)
        LogFacade.i(LogModule.PLAYER, TAG, "subtitle offset updated: $newOffsetMs ms")
        if (!player.playWhenReady) {
            renderOnce(player.contentPosition)
        }
    }

    fun currentOffset(): Long = SubtitlePreferenceUpdater.currentOffset()

    private fun renderOnce(positionMs: Long) {
        if (!shouldRender()) return
        val ptsMs = (positionMs + SubtitlePreferenceUpdater.currentOffset()).coerceAtLeast(0L)
        val vsyncId = SystemClock.elapsedRealtimeNanos() / 1_000_000L
        renderer.renderFrame(ptsMs, vsyncId)
    }

    companion object {
        private const val TAG = "SubtitleRenderSched"
    }
}
