package com.xyoye.player.kernel.impl.media3

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.view.Display
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
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
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.common_component.extension.mapByLength
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.impl.media3.Media3MediaSourceHelper.getMediaSource
import com.xyoye.player.kernel.impl.media3.AggressiveMediaCodecSelector
import com.xyoye.player.kernel.impl.media3.LibassAwareRenderersFactory
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.utils.PlayerConstant
import com.xyoye.subtitle.MixedSubtitle
import com.xyoye.subtitle.SubtitleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@UnstableApi
class Media3VideoPlayer(
    context: Context
) : AbstractVideoPlayer(),
    Player.Listener {
    private val appContext: Context = context.applicationContext

    private lateinit var player: ExoPlayer
    private lateinit var mediaSource: MediaSource

    private val trackSelector: TrackSelector by lazy { DefaultTrackSelector(appContext) }
    private val loadControl: LoadControl by lazy { DefaultLoadControl() }
    private lateinit var speedParameters: PlaybackParameters
    private var videoOverrideApplied = false

    private var subtitleType = SubtitleType.UN_KNOW
    private var isPreparing = false
    private var isBuffering = false
    private var lastReportedPlayWhenReady = false
    private var lastReportedPlaybackState = Player.STATE_IDLE

    // 将解码器回退的重试配额按轨道类型分别计数，避免音频失败占用视频回退配额
    private val maxVideoDecoderRecoveries = 3 // 三个候选解码器：常规/低时延/软件，各自最多尝试一次
    private val maxAudioDecoderRecoveries = 2
    private var videoDecoderRecoveryCount = 0
    private var audioDecoderRecoveryCount = 0

    private val trackNameProvider by lazy { DefaultTrackNameProvider(appContext.resources) }

    override fun initPlayer() {
        if (trackSelector is DefaultTrackSelector) {
            val preferredMimeTypes =
                Media3FormatUtil.preferredVideoMimeTypes(appContext).toTypedArray()
            trackSelector.parameters =
                DefaultTrackSelector.Parameters
                    .Builder(appContext)
                    .setPreferredTextLanguage("zh")
                    .setPreferredAudioLanguage("jap")
                    .setPreferredVideoMimeTypes(*preferredMimeTypes)
                    .build()
        }

        val renderersFactory =
            LibassAwareRenderersFactory(
                appContext,
                AggressiveMediaCodecSelector(),
            ).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            }

        player =
            ExoPlayer
                .Builder(appContext)
                .setRenderersFactory(renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(appContext))
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setBandwidthMeter(DefaultBandwidthMeter.Builder(appContext).build())
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

    override fun setDataSource(
        path: String,
        headers: Map<String, String>?
    ) {
        if (path.isEmpty()) {
            mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_URL_EMPTY, 0)
            return
        }
        mediaSource = getMediaSource(path, headers)
        videoOverrideApplied = false
        videoDecoderRecoveryCount = 0
        audioDecoderRecoveryCount = 0
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
        // 为单一 MediaSource 注入比特流修补（仅 H.264 avcC -> Annex-B，目前媒体管线已覆盖大部分情况）
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
        videoOverrideApplied = false
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

    override fun setVolume(
        leftVolume: Float,
        rightVolume: Float
    ) {
        player.volume = (leftVolume + rightVolume) / 2
    }

    override fun setLooping(isLooping: Boolean) {
        player.repeatMode = if (isLooping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    override fun setSubtitleOffset(offsetMs: Long) {
        // Media3 暂不支持直接设置字幕偏移，但 GPU 路径通过 SubtitlePreferenceUpdater 读取偏移。
        SubtitlePreferenceUpdater.persistOffset(offsetMs)
    }

    override fun isPlaying(): Boolean =
        when (player.playbackState) {
            Player.STATE_BUFFERING,
            Player.STATE_READY -> player.playWhenReady

            Player.STATE_IDLE,
            Player.STATE_ENDED -> false

            else -> false
        }

    override fun getCurrentPosition(): Long = player.contentPosition

    override fun getDuration(): Long = player.duration

    override fun getSpeed(): Float =
        if (this::speedParameters.isInitialized) {
            speedParameters.speed
        } else {
            1f
        }

    override fun getVideoSize(): Point {
        val size = player.videoSize
        return Point(size.width, size.height)
    }

    override fun getBufferedPercentage(): Int = player.bufferedPercentage

    override fun getTcpSpeed(): Long = 0L

    override fun supportAddTrack(type: TrackType): Boolean =
        when (type) {
            TrackType.AUDIO -> true
            TrackType.SUBTITLE -> false
            else -> false
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

        val override =
            player.currentTracks.groups.getOrNull(groupIndex)?.let {
                androidx.media3.common.TrackSelectionOverride(it.mediaTrackGroup, trackIndex)
            } ?: return

        trackSelector.parameters =
            DefaultTrackSelector.Parameters
                .Builder(appContext)
                .setTrackTypeDisabled(media3Type, false)
                .clearOverridesOfType(media3Type)
                .addOverride(override)
                .build()
    }

    override fun deselectTrack(type: TrackType) {
        val media3Type = getTrackType(type) ?: return
        trackSelector.parameters =
            DefaultTrackSelector.Parameters
                .Builder(appContext)
                .setTrackTypeDisabled(media3Type, true)
                .build()
    }

    private fun getTrackType(type: TrackType): Int? =
        when (type) {
            TrackType.AUDIO -> C.TRACK_TYPE_AUDIO
            TrackType.SUBTITLE -> C.TRACK_TYPE_TEXT
            else -> null
        }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        mPlayerEventListener.onVideoSizeChange(videoSize.width, videoSize.height)
        if (videoSize.unappliedRotationDegrees > 0) {
            mPlayerEventListener.onInfo(
                PlayerConstant.MEDIA_INFO_VIDEO_ROTATION_CHANGED,
                videoSize.unappliedRotationDegrees,
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
                        getBufferedPercentage(),
                    )
                }

                Player.STATE_READY -> {
                    if (isBuffering) {
                        mPlayerEventListener.onInfo(
                            PlayerConstant.MEDIA_INFO_BUFFERING_END,
                            getBufferedPercentage(),
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
        applyHdrSdrPreference(tracks)
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        if (tryRecoverFromDecoderError(error)) {
            return
        }
        mPlayerEventListener.onError(error)
    }

    override fun onCues(cues: MutableList<Cue>) {
        super.onCues(cues)
        if (subtitleType == SubtitleType.UN_KNOW && cues.isEmpty()) {
            return
        }

        if (subtitleType == SubtitleType.UN_KNOW) {
            subtitleType =
                if (cues.firstOrNull()?.bitmap != null) {
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
            val subtitleText =
                if (builder.isNotEmpty()) {
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

    private fun applyHdrSdrPreference(tracks: Tracks) {
        if (videoOverrideApplied) return
        val selector = trackSelector as? DefaultTrackSelector ?: return

        val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        if (videoGroups.isEmpty()) return

        val displaySupport =
            DisplayHdrSupport.fromTypes(Media3FormatUtil.getDisplayHdrTypes(appContext))
        val supportsHdr = displaySupport.supportsAnyHdr

        var bestGroupIdx = -1
        var bestTrackIdx = -1
        var bestScore = Int.MIN_VALUE

        videoGroups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val tier = hdrTier(format, displaySupport) // 4:DV, 3:HDR10(+), 2:HLG, 1:SDR

                val hdrScore =
                    if (supportsHdr) {
                        tier
                    } else if (tier > 1) {
                        0
                    } else {
                        1
                    }
                val height = format.height
                val bitrate = format.bitrate
                val frameRate = format.frameRate.takeUnless { it.isNaN() || it <= 0f } ?: 0f
                val frameRateScore = (frameRate * 500).roundToInt() // 60fps≈30k，远小于 HDR/分辨率
                val score = hdrScore * 1_000_000_000 + height * 10_000 + bitrate / 1000 + frameRateScore
                if (score > bestScore) {
                    bestScore = score
                    bestGroupIdx = groupIndex
                    bestTrackIdx = trackIndex
                }
            }
        }

        if (bestGroupIdx >= 0 && bestTrackIdx >= 0) {
            val group = videoGroups[bestGroupIdx]
            val override = androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, bestTrackIdx)
            selector.parameters =
                selector.parameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .addOverride(override)
                    .build()
            videoOverrideApplied = true
            val selectedFormat = group.getTrackFormat(bestTrackIdx)
            Media3Diagnostics.logHdrSelection(selectedFormat, supportsHdr, hdrTier(selectedFormat, displaySupport))
        }
    }

    private fun hdrTier(
        format: androidx.media3.common.Format,
        displaySupport: DisplayHdrSupport
    ): Int {
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            val descriptor = Media3FormatUtil.describeDolbyVision(format)
            val displayInfo = displaySupport.describe()
            return when {
                displaySupport.supportsDv -> 4
                Media3FormatUtil.hasHdr10Fallback(descriptor) && displaySupport.supportsHdr10Family -> {
                    Media3Diagnostics.logDolbyVisionFallback(format.codecs, "HDR10", displayInfo)
                    3
                }
                Media3FormatUtil.hasHlgFallback(descriptor) && displaySupport.supportsHlg -> {
                    Media3Diagnostics.logDolbyVisionFallback(format.codecs, "HLG", displayInfo)
                    2
                }
                Media3FormatUtil.hasSdrFallback(descriptor) -> {
                    Media3Diagnostics.logDolbyVisionFallback(format.codecs, "SDR", displayInfo)
                    1
                }
                else -> {
                    Media3Diagnostics.logDolbyVisionFallback(format.codecs, "UNSUPPORTED", displayInfo)
                    0
                }
            }
        }
        val hasHdrStatic = format.colorInfo?.hdrStaticInfo != null
        val transfer = format.colorInfo?.colorTransfer
        return when {
            hasHdrStatic -> if (displaySupport.supportsHdr10Family) 3 else 2
            transfer == C.COLOR_TRANSFER_HLG -> if (displaySupport.supportsHlg) 2 else 1
            else -> 1
        }
    }

    @SuppressLint("InlinedApi")
    private data class DisplayHdrSupport(
        val supportsDv: Boolean,
        val supportsHdr10Plus: Boolean,
        val supportsHdr10: Boolean,
        val supportsHlg: Boolean
    ) {
        val supportsAnyHdr: Boolean
            get() = supportsDv || supportsHdr10Plus || supportsHdr10 || supportsHlg

        val supportsHdr10Family: Boolean
            get() = supportsDv || supportsHdr10Plus || supportsHdr10

        companion object {
            fun fromTypes(types: IntArray): DisplayHdrSupport {
                val hdrSet = types.toSet()
                return DisplayHdrSupport(
                    supportsDv = hdrSet.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION),
                    supportsHdr10Plus = hdrSet.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS),
                    supportsHdr10 = hdrSet.contains(Display.HdrCapabilities.HDR_TYPE_HDR10),
                    supportsHlg = hdrSet.contains(Display.HdrCapabilities.HDR_TYPE_HLG),
                )
            }
        }

        fun describe(): String = "dv=$supportsDv,hdr10Plus=$supportsHdr10Plus,hdr10=$supportsHdr10,hlg=$supportsHlg"
    }

    private fun tryRecoverFromDecoderError(error: PlaybackException): Boolean {
        if (!this::mediaSource.isInitialized) {
            return false
        }
        // 不使用回退 MIME，交由异常自带信息判断，以便正确区分音频/视频失败
        val failure =
            Media3CodecPolicy.decoderFailureFromException(error, fallbackMimeType = null)
                ?: return false
        val blacklisted = Media3CodecPolicy.recordDecoderFailure(failure)
        if (!blacklisted) {
            Media3Diagnostics.logDecoderGiveUp(failure.decoderName, failure.diagnosticInfo)
            return false
        }
        // 依据失败的 MIME/解码器名称推断轨道类型
        val mime = failure.mimeType
        val nameLower = failure.decoderName.lowercase()
        val isVideoFailure =
            when {
                mime?.startsWith("video/") == true -> true
                mime?.startsWith("audio/") == true -> false
                // 无法从 MIME 判断时，用常见编解码器名猜测
                nameLower.contains("hevc") || nameLower.contains("avc") || nameLower.contains("vp9") || nameLower.contains("av1") -> true
                nameLower.contains("flac") ||
                    nameLower.contains("aac") ||
                    nameLower.contains("opus") ||
                    nameLower.contains("vorbis") ||
                    nameLower.contains("mp3") -> false
                else -> true // 保守认定为视频失败
            }

        if (isVideoFailure) {
            if (videoDecoderRecoveryCount >= maxVideoDecoderRecoveries) {
                Media3Diagnostics.logDecoderGiveUp(failure.decoderName, failure.diagnosticInfo)
                return false
            }
            videoDecoderRecoveryCount++
            Media3Diagnostics.logDecoderRetry(failure.decoderName, videoDecoderRecoveryCount)
        } else {
            if (audioDecoderRecoveryCount >= maxAudioDecoderRecoveries) {
                Media3Diagnostics.logDecoderGiveUp(failure.decoderName, failure.diagnosticInfo)
                return false
            }
            audioDecoderRecoveryCount++
            Media3Diagnostics.logDecoderRetry(failure.decoderName, audioDecoderRecoveryCount)
        }
        restartAfterDecoderFailure()
        return true
    }

    private fun restartAfterDecoderFailure() {
        val resumePosition = player.contentPosition
        val playWhenReady = player.playWhenReady
        isPreparing = true
        isBuffering = false
        player.stop()
        videoOverrideApplied = false
        player.setMediaSource(mediaSource)
        if (this::speedParameters.isInitialized) {
            player.playbackParameters = speedParameters
        }
        player.prepare()
        if (resumePosition > 0) {
            player.seekTo(resumePosition)
        }
        player.playWhenReady = playWhenReady
    }
}
