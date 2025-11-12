package com.xyoye.player.kernel.impl.media3

import android.content.Context
import android.graphics.Point
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.DefaultTrackNameProvider
import com.xyoye.common_component.extension.mapByLength
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.impl.media3.Media3MediaSourceHelper.getMediaSource
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.utils.PlayerConstant
import com.xyoye.subtitle.MixedSubtitle
import com.xyoye.subtitle.SubtitleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@UnstableApi
class Media3VideoPlayer(private val context: Context) : AbstractVideoPlayer(), Player.Listener {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSource: MediaSource

    private val trackSelector: TrackSelector by lazy { DefaultTrackSelector(context) }
    private val loadControl: LoadControl by lazy { DefaultLoadControl() }
    private lateinit var speedParameters: PlaybackParameters

    private var subtitleType = SubtitleType.UN_KNOW
    private var isPreparing = false
    private var isBuffering = false
    private var lastReportedPlayWhenReady = false
    private var lastReportedPlaybackState = Player.STATE_IDLE

    private val trackNameProvider by lazy { DefaultTrackNameProvider(context.resources) }

    override fun initPlayer() {
        if (trackSelector is DefaultTrackSelector) {
            trackSelector.parameters = DefaultTrackSelector.Parameters.Builder(context)
                .setPreferredTextLanguage("zh")
                .setPreferredAudioLanguage("jap")
                .build()
        }

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(DefaultBandwidthMeter.Builder(context).build())
            .setAnalyticsCollector(DefaultAnalyticsCollector(Clock.DEFAULT))
            .build()

        setOptions()

        if (PlayerInitializer.isPrintLog && trackSelector is MappingTrackSelector) {
            player.addAnalyticsListener(EventLogger(Media3VideoPlayer::class.java.simpleName))
        }

        initListener()
    }

    override fun setOptions() {
        player.playWhenReady = true
    }

    override fun setDataSource(path: String, headers: Map<String, String>?) {
        if (path.isEmpty()) {
            mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_URL_EMPTY, 0)
            return
        }
        mediaSource = getMediaSource(path, headers)
    }

    override fun setSurface(surface: Surface) {
        player.setVideoSurface(surface)
    }

    override fun prepareAsync() {
        if (!this::mediaSource.isInitialized) {
            return
        }
        if (this::speedParameters.isInitialized) {
            player.playbackParameters = speedParameters
        }

        isPreparing = true
        player.setMediaSource(mediaSource)
        player.prepare()
    }

    override fun start() {
        player.playWhenReady = true
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun stop() {
        player.stop()
    }

    override fun reset() {
        player.stop()
        player.setVideoSurface(null)
        isPreparing = false
        isBuffering = false
        lastReportedPlaybackState = Player.STATE_IDLE
        lastReportedPlayWhenReady = false
    }

    override fun release() {
        player.apply {
            removeListener(this@Media3VideoPlayer)
            SupervisorScope.IO.launch(Dispatchers.Main) {
                release()
            }
        }
        isPreparing = false
        isBuffering = false
        lastReportedPlaybackState = Player.STATE_IDLE
        lastReportedPlayWhenReady = false
    }

    override fun seekTo(timeMs: Long) {
        player.seekTo(timeMs)
    }

    override fun setSpeed(speed: Float) {
        speedParameters = PlaybackParameters(speed)
        player.playbackParameters = speedParameters
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        player.volume = (leftVolume + rightVolume) / 2
    }

    override fun setLooping(isLooping: Boolean) {
        player.repeatMode = if (isLooping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    override fun setSubtitleOffset(offsetMs: Long) {
        // Not supported for Media3 yet.
    }

    override fun isPlaying(): Boolean {
        return when (player.playbackState) {
            Player.STATE_BUFFERING,
            Player.STATE_READY -> player.playWhenReady

            Player.STATE_IDLE,
            Player.STATE_ENDED -> false

            else -> false
        }
    }

    override fun getCurrentPosition(): Long = player.contentPosition

    override fun getDuration(): Long = player.duration

    override fun getSpeed(): Float {
        return if (this::speedParameters.isInitialized) {
            speedParameters.speed
        } else {
            1f
        }
    }

    override fun getVideoSize(): Point {
        val size = player.videoSize
        return Point(size.width, size.height)
    }

    override fun getBufferedPercentage(): Int = player.bufferedPercentage

    override fun getTcpSpeed(): Long = 0L

    override fun supportAddTrack(type: TrackType): Boolean {
        return when (type) {
            TrackType.AUDIO -> true
            TrackType.SUBTITLE -> false
            else -> false
        }
    }

    override fun addTrack(track: VideoTrackBean): Boolean {
        // External track injection is not supported in the Media3 pipeline yet.
        return false
    }

    override fun getTracks(type: TrackType): List<VideoTrackBean> {
        val media3Type = getTrackType(type) ?: return emptyList()
        return player.currentTracks.groups.flatMapIndexed { groupIndex, group ->
            if (group.type != media3Type) {
                return@flatMapIndexed emptyList()
            }
            mapByLength(group.length) { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                val name = trackNameProvider.getTrackName(format)
                val selected = group.isTrackSelected(trackIndex)
                val id = "$groupIndex-$trackIndex"
                VideoTrackBean.internal(id, name, type, selected)
            }
        }
    }

    override fun selectTrack(track: VideoTrackBean) {
        val media3Type = getTrackType(track.type) ?: return
        val trackIds = track.id?.split("-") ?: return
        val groupIndex = trackIds.getOrNull(0)?.toInt() ?: return
        val trackIndex = trackIds.getOrNull(1)?.toInt() ?: return

        val override = player.currentTracks.groups.getOrNull(groupIndex)?.let {
            androidx.media3.common.TrackSelectionOverride(it.mediaTrackGroup, trackIndex)
        } ?: return

        trackSelector.parameters = DefaultTrackSelector.Parameters.Builder(context)
            .setTrackTypeDisabled(media3Type, false)
            .clearOverridesOfType(media3Type)
            .addOverride(override)
            .build()
    }

    override fun deselectTrack(type: TrackType) {
        val media3Type = getTrackType(type) ?: return
        trackSelector.parameters = DefaultTrackSelector.Parameters.Builder(context)
            .setTrackTypeDisabled(media3Type, true)
            .build()
    }

    private fun getTrackType(type: TrackType): Int? {
        return when (type) {
            TrackType.AUDIO -> C.TRACK_TYPE_AUDIO
            TrackType.SUBTITLE -> C.TRACK_TYPE_TEXT
            else -> null
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        mPlayerEventListener.onVideoSizeChange(videoSize.width, videoSize.height)
        if (videoSize.unappliedRotationDegrees > 0) {
            mPlayerEventListener.onInfo(
                PlayerConstant.MEDIA_INFO_VIDEO_ROTATION_CHANGED,
                videoSize.unappliedRotationDegrees
            )
        }
    }

    override fun onRenderedFirstFrame() {
        mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_VIDEO_RENDERING_START, 0)
        isPreparing = false
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (isPreparing && state == Player.STATE_READY) {
            mPlayerEventListener.onPrepared()
            isPreparing = false
        }
        if (isPreparing) {
            return
        }
        val playWhenReady = player.playWhenReady
        if (lastReportedPlayWhenReady != playWhenReady || lastReportedPlaybackState != state) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    isBuffering = true
                    mPlayerEventListener.onInfo(
                        PlayerConstant.MEDIA_INFO_BUFFERING_START,
                        getBufferedPercentage()
                    )
                }

                Player.STATE_READY -> {
                    if (isBuffering) {
                        mPlayerEventListener.onInfo(
                            PlayerConstant.MEDIA_INFO_BUFFERING_END,
                            getBufferedPercentage()
                        )
                        isBuffering = false
                    }
                }

                Player.STATE_ENDED -> mPlayerEventListener.onCompletion()
                else -> Unit
            }
        }
        lastReportedPlaybackState = state
        lastReportedPlayWhenReady = playWhenReady
    }

    override fun onTracksChanged(tracks: Tracks) {
        subtitleType = SubtitleType.UN_KNOW
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        mPlayerEventListener.onError(error)
    }

    override fun onCues(cues: MutableList<Cue>) {
        super.onCues(cues)
        if (subtitleType == SubtitleType.UN_KNOW && cues.isEmpty()) {
            return
        }

        if (subtitleType == SubtitleType.UN_KNOW) {
            subtitleType = if (cues.firstOrNull()?.bitmap != null) {
                SubtitleType.BITMAP
            } else {
                SubtitleType.TEXT
            }
        }

        if (subtitleType == SubtitleType.BITMAP) {
            mPlayerEventListener.onSubtitleTextOutput(MixedSubtitle.fromBitmap(cues))
        } else {
            val builder = StringBuilder()
            cues.forEach { cue -> builder.append(cue.text).append("\n") }
            val subtitleText = if (builder.isNotEmpty()) {
                builder.substring(0, builder.length - 1)
            } else {
                builder.toString()
            }
            mPlayerEventListener.onSubtitleTextOutput(MixedSubtitle.fromText(subtitleText))
        }
    }

    private fun initListener() {
        player.addListener(this)
    }
}
