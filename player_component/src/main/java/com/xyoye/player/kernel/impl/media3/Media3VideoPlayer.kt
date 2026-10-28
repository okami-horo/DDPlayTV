package com.xyoye.player.kernel.impl.media3

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
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
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.common_component.extension.mapByLength
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.storage.file.helper.LocalProxy
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.impl.media3.Media3MediaSourceHelper.getMediaSource
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.kernel.subtitle.SubtitleFrameDriver
import com.xyoye.player.kernel.subtitle.SubtitleKernelBridge
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import com.xyoye.player.utils.PlayerConstant
import com.xyoye.subtitle.MixedSubtitle
import com.xyoye.subtitle.SubtitleType
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

@UnstableApi
class Media3VideoPlayer(
    context: Context
) : AbstractVideoPlayer(),
    Player.Listener,
    SubtitleKernelBridge {
    private val appContext: Context = context.applicationContext

    private lateinit var player: ExoPlayer
    private lateinit var mediaSource: MediaSource
    private var currentDataSource: String? = null
    private var currentDataSourceHeaders: Map<String, String>? = null
    private var currentSurface: Surface? = null

    private val embeddedSubtitleSink = AtomicReference<EmbeddedSubtitleSink?>()

    private val trackSelector: TrackSelector by lazy { DefaultTrackSelector(appContext) }
    private val loadControl: LoadControl by lazy { DefaultLoadControl() }
    private lateinit var speedParameters: PlaybackParameters
    private var videoOverrideApplied = false

    private var subtitleType = SubtitleType.UN_KNOW
    private var hasSsaLikeTextTrack = false
    private var hasMedia3CueTextTrack = false
    private var isPreparing = false
    private var isBuffering = false
    private var lastReportedPlayWhenReady = false
    private var lastReportedPlaybackState = Player.STATE_IDLE
    private var currentParseSubtitlesDuringExtraction = shouldParseSubtitlesDuringExtraction()

    private val subtitleFrameDrivers = mutableSetOf<SubtitleFrameDriver>()

    // 将解码器回退的重试配额按轨道类型分别计数，避免音频失败占用视频回退配额
    private val maxVideoDecoderRecoveries = 3 // 三个候选解码器：常规/低时延/软件，各自最多尝试一次
    private val maxAudioDecoderRecoveries = 2
    private var videoDecoderRecoveryCount = 0
    private var audioDecoderRecoveryCount = 0

    private val trackNameProvider by lazy { DefaultTrackNameProvider(appContext.resources) }

    override fun initPlayer() {
        if (this::player.isInitialized) {
            return
        }

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

        player = buildPlayer()

        if (PlayerInitializer.isPrintLog && trackSelector is MappingTrackSelector) {
            player.addAnalyticsListener(EventLogger(Media3VideoPlayer::class.java.simpleName))
        }

        setOptions()
        initListener()
    }

    private fun buildPlayer(): ExoPlayer {
        val embeddedSinkRef = embeddedSubtitleSink
        val renderersFactory =
            LibassAwareRenderersFactory(
                appContext,
                AggressiveMediaCodecSelector(),
                embeddedSinkProvider = { embeddedSinkRef.get() },
            ).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            }

        return ExoPlayer
            .Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(createMediaSourceFactory())
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(DefaultBandwidthMeter.Builder(appContext).build())
            .setAnalyticsCollector(DefaultAnalyticsCollector(Clock.DEFAULT))
            .build()
    }

    private fun createMediaSourceFactory(): DefaultMediaSourceFactory {
        val parseSubtitlesDuringExtraction = shouldParseSubtitlesDuringExtraction()
        currentParseSubtitlesDuringExtraction = parseSubtitlesDuringExtraction
        if (PlayerInitializer.isPrintLog) {
            LogFacade.d(
                LogModule.PLAYER,
                "PlayerSubtitle",
                "media3 sourceFactory parseSubtitlesDuringExtraction=$parseSubtitlesDuringExtraction backend=${PlayerInitializer.Subtitle.backend}",
            )
        }
        return DefaultMediaSourceFactory(appContext)
            .experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
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
        currentDataSource = path
        currentDataSourceHeaders = headers
        runCatching {
            LocalProxy.setSeekEnabledIfServing(path, enabled = false)
        }
        val parseSubtitlesDuringExtraction = shouldParseSubtitlesDuringExtraction()
        currentParseSubtitlesDuringExtraction = parseSubtitlesDuringExtraction
        mediaSource = getMediaSource(path, headers, parseSubtitlesDuringExtraction = parseSubtitlesDuringExtraction)
        videoOverrideApplied = false
        videoDecoderRecoveryCount = 0
        audioDecoderRecoveryCount = 0
    }

    override fun setSurface(surface: Surface) {
        currentSurface = surface
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
        currentSurface = null
        isPreparing = false
        isBuffering = false
        lastReportedPlaybackState = Player.STATE_IDLE
        lastReportedPlayWhenReady = false
        videoOverrideApplied = false
    }

    override fun release() {
        clearPlayerEventListener()
        if (!this::player.isInitialized) {
            return
        }

        val playerToRelease = player

        val releaseAction = {
            releaseSubtitleFrameDrivers()
            embeddedSubtitleSink.set(null)
            currentDataSource = null
            currentDataSourceHeaders = null
            currentSurface = null
            playerToRelease.run {
                runCatching { removeListener(this@Media3VideoPlayer) }
                runCatching { setVideoSurface(null) }
                runCatching { release() }
            }
        }

        if (Looper.myLooper() == playerToRelease.applicationLooper) {
            releaseAction()
        } else {
            Handler(playerToRelease.applicationLooper).post { releaseAction() }
        }

        isPreparing = false
        isBuffering = false
        lastReportedPlaybackState = Player.STATE_IDLE
        lastReportedPlayWhenReady = false
    }

    override fun seekTo(timeMs: Long) {
        player.seekTo(timeMs)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            mPlayerEventListener.onSeekComplete()
        }
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

    internal fun exoPlayerOrNull(): ExoPlayer? =
        if (this::player.isInitialized) {
            player
        } else {
            null
        }

    override fun canStartGpuSubtitlePipeline(): Boolean = true

    override fun setEmbeddedSubtitleSink(sink: EmbeddedSubtitleSink?) {
        embeddedSubtitleSink.set(sink)
    }

    override fun onSubtitleBackendChanged(target: SubtitleRendererBackend) {
        val targetParseSubtitlesDuringExtraction = target != SubtitleRendererBackend.LIBASS
        if (targetParseSubtitlesDuringExtraction == currentParseSubtitlesDuringExtraction) {
            return
        }
        currentParseSubtitlesDuringExtraction = targetParseSubtitlesDuringExtraction
        if (PlayerInitializer.isPrintLog) {
            LogFacade.d(
                LogModule.PLAYER,
                "PlayerSubtitle",
                "media3 backend changed target=$target parseSubtitlesDuringExtraction=$targetParseSubtitlesDuringExtraction hasSource=${!currentDataSource.isNullOrBlank()}",
            )
        }
        if (!this::player.isInitialized || currentDataSource.isNullOrBlank()) {
            return
        }
        rebuildPlayer()
    }

    override fun createFrameDriver(callback: SubtitleFrameDriver.Callback): SubtitleFrameDriver? {
        val exoPlayer = exoPlayerOrNull() ?: return null
        return Media3SubtitleFrameDriver(exoPlayer, callback).also { driver ->
            subtitleFrameDrivers.add(driver)
        }
    }

    private fun releaseSubtitleFrameDrivers() {
        subtitleFrameDrivers.forEach { driver ->
            runCatching { driver.stop() }
        }
        subtitleFrameDrivers.clear()
    }

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
        val selector = trackSelector as? DefaultTrackSelector ?: return
        val trackIds = track.id?.split("-") ?: return
        val groupIndex = trackIds.getOrNull(0)?.toInt() ?: return
        val trackIndex = trackIds.getOrNull(1)?.toInt() ?: return

        val override =
            player.currentTracks.groups.getOrNull(groupIndex)?.let {
                androidx.media3.common.TrackSelectionOverride(it.mediaTrackGroup, trackIndex)
            } ?: return

        selector.parameters =
            selector.parameters
                .buildUpon()
                .setTrackTypeDisabled(media3Type, false)
                .clearOverridesOfType(media3Type)
                .addOverride(override)
                .build()
    }

    override fun deselectTrack(type: TrackType) {
        val media3Type = getTrackType(type) ?: return
        val selector = trackSelector as? DefaultTrackSelector ?: return
        selector.parameters =
            selector.parameters
                .buildUpon()
                .setTrackTypeDisabled(media3Type, true)
                .clearOverridesOfType(media3Type)
                .build()
    }

    private fun getTrackType(type: TrackType): Int? =
        when (type) {
            TrackType.VIDEO -> C.TRACK_TYPE_VIDEO
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
        val path = currentDataSource
        if (!path.isNullOrBlank()) {
            runCatching {
                LocalProxy.setSeekEnabledIfServing(path, enabled = true)
            }
        }
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
        logTextTracks(tracks)
        applyHdrSdrPreference(tracks)
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        if (tryRecoverFromDecoderError(error)) {
            return
        }
        mPlayerEventListener.onError(error)
    }

    private fun rebuildPlayer() {
        if (!this::player.isInitialized) {
            return
        }
        val playerLooper = player.applicationLooper
        if (Looper.myLooper() != playerLooper) {
            Handler(playerLooper).post { rebuildPlayerInternal() }
            return
        }
        rebuildPlayerInternal()
    }

    private fun rebuildPlayerInternal() {
        if (!this::player.isInitialized) {
            return
        }

        val oldPlayer = player
        val resumePosition = oldPlayer.contentPosition
        val playWhenReady = oldPlayer.playWhenReady
        val repeatMode = oldPlayer.repeatMode
        val volume = oldPlayer.volume
        val playbackParameters =
            if (this::speedParameters.isInitialized) {
                speedParameters
            } else {
                null
            }
        val dataSource = currentDataSource
        val headers = currentDataSourceHeaders
        val surface = currentSurface

        releaseSubtitleFrameDrivers()
        runCatching { oldPlayer.removeListener(this) }
        runCatching { oldPlayer.setVideoSurface(null) }
        runCatching { oldPlayer.release() }

        player = buildPlayer()
        videoOverrideApplied = false
        subtitleType = SubtitleType.UN_KNOW
        isPreparing = false
        isBuffering = false
        lastReportedPlaybackState = Player.STATE_IDLE
        lastReportedPlayWhenReady = false

        if (PlayerInitializer.isPrintLog && trackSelector is MappingTrackSelector) {
            player.addAnalyticsListener(EventLogger(Media3VideoPlayer::class.java.simpleName))
        }

        setOptions()
        initListener()

        playbackParameters?.let { player.playbackParameters = it }
        player.repeatMode = repeatMode
        player.volume = volume
        surface?.let { player.setVideoSurface(it) }

        if (!dataSource.isNullOrBlank()) {
            val parseSubtitlesDuringExtraction = shouldParseSubtitlesDuringExtraction()
            currentParseSubtitlesDuringExtraction = parseSubtitlesDuringExtraction
            mediaSource = getMediaSource(dataSource, headers, parseSubtitlesDuringExtraction = parseSubtitlesDuringExtraction)
            isPreparing = true
            player.setMediaSource(mediaSource)
            player.prepare()
            if (resumePosition > 0) {
                player.seekTo(resumePosition)
            }
            player.playWhenReady = playWhenReady
        }

        player.playWhenReady = playWhenReady
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
            if (PlayerInitializer.isPrintLog) {
                val firstCue = cues.firstOrNull()
                val cueSummary =
                    if (subtitleType == SubtitleType.BITMAP) {
                        val bmp = firstCue?.bitmap
                        "bitmap=${bmp?.width ?: -1}x${bmp?.height ?: -1}"
                    } else {
                        val text = firstCue?.text?.toString().orEmpty()
                        "textLen=${text.length}"
                    }
                LogFacade.d(
                    LogModule.PLAYER,
                    "PlayerSubtitle",
                    "media3 cues type=$subtitleType count=${cues.size} $cueSummary backend=${PlayerInitializer.Subtitle.backend} sinkPresent=${embeddedSubtitleSink.get() != null} expectBypass=${isLibassBypassExpected()} ssaTrack=$hasSsaLikeTextTrack cuesTrack=$hasMedia3CueTextTrack",
                )
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

    private fun logTextTracks(tracks: Tracks) {
        if (!PlayerInitializer.isPrintLog) {
            return
        }
        val selector = trackSelector as? DefaultTrackSelector
        val textRendererIndex =
            (0 until player.rendererCount)
                .firstOrNull { index -> player.getRendererType(index) == C.TRACK_TYPE_TEXT }
        val textDisabled =
            if (selector != null && textRendererIndex != null) {
                selector.parameters.getRendererDisabled(textRendererIndex)
            } else {
                false
            }

        var hasTextTracks = false
        var ssaLikeTrack = false
        var media3CueTrack = false
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_TEXT) {
                return@forEachIndexed
            }
            hasTextTracks = true
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val isSsaTrack = Media3SubtitleFormatClassifier.isSsaFamilyTrack(format)
                val isMedia3CueTrack = Media3SubtitleFormatClassifier.isMedia3CueFormat(format)
                val ssaCodec = Media3SubtitleFormatClassifier.isSsaCodecs(format.codecs)
                if (isSsaTrack) {
                    ssaLikeTrack = true
                }
                if (isMedia3CueTrack) {
                    media3CueTrack = true
                }
                val name = trackNameProvider.getTrackName(format)
                val initDataSummary = formatInitializationDataSummary(format)
                LogFacade.d(
                    LogModule.PLAYER,
                    "PlayerSubtitle",
                    "media3 textTrack g=$groupIndex t=$trackIndex selected=${group.isTrackSelected(trackIndex)} supported=${group.isTrackSupported(trackIndex)} disabled=$textDisabled mime=${format.sampleMimeType} codecs=${format.codecs} label=${format.label} lang=${format.language} name=$name ssaLike=$isSsaTrack ssaCodec=$ssaCodec media3Cue=$isMedia3CueTrack initData=$initDataSummary",
                )
            }
        }

        hasSsaLikeTextTrack = ssaLikeTrack
        hasMedia3CueTextTrack = media3CueTrack

        if (!hasTextTracks) {
            hasSsaLikeTextTrack = false
            hasMedia3CueTextTrack = false
            LogFacade.d(LogModule.PLAYER, "PlayerSubtitle", "media3 textTracks: none disabled=$textDisabled")
        }
    }

    private fun isLibassBypassExpected(): Boolean =
        PlayerInitializer.Subtitle.backend == SubtitleRendererBackend.LIBASS &&
            hasSsaLikeTextTrack

    private fun shouldParseSubtitlesDuringExtraction(): Boolean =
        PlayerInitializer.Subtitle.backend != SubtitleRendererBackend.LIBASS

    private fun formatInitializationDataSummary(format: Format): String {
        val initData = format.initializationData
        if (initData.isEmpty()) {
            return "count=0"
        }
        val summary =
            initData.mapIndexed { index, bytes ->
                "#$index(size=${bytes.size},hex=${bytes.toHexPreview(INIT_DATA_PREVIEW_BYTES)})"
            }
        return "count=${initData.size} ${summary.joinToString(separator = ";")}"
    }

    private fun ByteArray.toHexPreview(maxBytes: Int): String {
        if (isEmpty() || maxBytes <= 0) {
            return "-"
        }
        val previewSize = minOf(size, maxBytes)
        val builder = StringBuilder(previewSize * 2 + 16)
        for (index in 0 until previewSize) {
            if (index > 0 && index % 2 == 0) {
                builder.append(' ')
            }
            val value = this[index].toInt() and 0xFF
            builder.append(HEX_DIGITS[value ushr 4])
            builder.append(HEX_DIGITS[value and 0x0F])
        }
        if (size > previewSize) {
            builder.append("...")
        }
        return builder.toString()
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
        var bestScore = Long.MIN_VALUE

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
                val score =
                    hdrScore.toLong() * 1_000_000_000L +
                        height.toLong() * 10_000L +
                        (bitrate / 1000).toLong() +
                        frameRateScore.toLong()
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

    private companion object {
        private const val INIT_DATA_PREVIEW_BYTES = 64
        private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()
    }
}
