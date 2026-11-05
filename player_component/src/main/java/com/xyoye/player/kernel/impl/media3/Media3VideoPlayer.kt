package com.xyoye.player.kernel.impl.media3

import android.content.Context
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.DefaultTrackNameProvider
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.extension.mapByLength
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.storage.file.helper.LocalProxy
import com.xyoye.common_component.weight.ToastCenter
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
    private var currentSurface: Surface? = null

    private var subtitleInput = Media3SubtitleInput()
    private var subtitleSink: EmbeddedSubtitleSink? = null

    private val trackSelector: TrackSelector by lazy { DefaultTrackSelector(appContext) }
    private val loadControl: LoadControl by lazy { DefaultLoadControl() }
    private lateinit var speedParameters: PlaybackParameters

    private var isPreparing = false
    private var isBuffering = false
    private var lastReportedPlayWhenReady = false
    private var lastReportedPlaybackState = Player.STATE_IDLE

    // Enqueue even on the main thread: fast IO must not finish before addTrack returns its Job.
    private val subtitleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var subtitleLoadJob: Job? = null
    private var pendingSubtitleId: String? = null
    private var pendingSubtitleAdded: ((VideoTrackBean) -> Unit)? = null
    private var pendingSubtitleTrack: VideoTrackBean? = null
    private var subtitleLoadRevision = 0L
    private var appliedSubtitleOffsetMs = 0L
    private val subtitleCacheDirectory = File(appContext.cacheDir, "media3-subtitles/${UUID.randomUUID()}")

    private val subtitleFrameDrivers = mutableSetOf<SubtitleFrameDriver>()

    private val trackNameProvider by lazy { DefaultTrackNameProvider(appContext.resources) }
    private val subtitleTrackNameProvider by lazy { Media3SubtitleTrackNameProvider(appContext.resources) }

    override fun initPlayer() {
        if (this::player.isInitialized) {
            return
        }

        if (trackSelector is DefaultTrackSelector) {
            trackSelector.parameters =
                DefaultTrackSelector.Parameters
                    .Builder(appContext)
                    .setPreferredTextLanguage("zh")
                    .setPreferredAudioLanguage("jap")
                    .build()
        }

        appliedSubtitleOffsetMs = SubtitlePreferenceUpdater.currentOffset()
        player = buildPlayer()

        if (PlayerInitializer.isPrintLog && trackSelector is MappingTrackSelector) {
            player.addAnalyticsListener(EventLogger(Media3VideoPlayer::class.java.simpleName))
        }

        setOptions()
        initListener()
    }

    private fun buildPlayer(): ExoPlayer {
        val renderersFactory =
            LibassAwareRenderersFactory(appContext).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            }

        return ExoPlayer
            .Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(Media3MediaSourceHelper.createMediaSourceFactory())
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(DefaultBandwidthMeter.Builder(appContext).build())
            .setAnalyticsCollector(DefaultAnalyticsCollector(Clock.DEFAULT))
            .build()
            .apply { addAnalyticsListener(Media3Diagnostics.createDecoderListener()) }
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
        runCatching {
            LocalProxy.setSeekEnabledIfServing(path, enabled = false)
        }
        cancelSubtitleLoad()
        mediaSource = getMediaSource(path, headers)
        activateSubtitleInput(mediaSource.mediaItem)
    }

    override fun setSurface(surface: Surface) {
        currentSurface = surface
        player.setVideoSurface(surface)
    }

    fun clearSurface(surface: Surface) {
        if (currentSurface !== surface) return
        currentSurface = null
        player.clearVideoSurface(surface)
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
        cancelSubtitleLoad()
        player.stop()
    }

    override fun reset() {
        cancelSubtitleLoad()
        player.stop()
        player.setVideoSurface(null)
        currentSurface = null
        isPreparing = false
        isBuffering = false
        lastReportedPlaybackState = Player.STATE_IDLE
        lastReportedPlayWhenReady = false
    }

    override fun release() {
        clearPlayerEventListener()
        if (!this::player.isInitialized) {
            return
        }

        val playerToRelease = player

        val releaseAction = {
            subtitleScope.cancel()
            releaseSubtitleFrameDrivers()
            subtitleInput.setSink(null)
            subtitleInput.beginMedia()
            subtitleSink = null
            currentDataSource = null
            currentSurface = null
            playerToRelease.run {
                runCatching { removeListener(this@Media3VideoPlayer) }
                runCatching { setVideoSurface(null) }
                runCatching { release() }
            }
            subtitleCacheDirectory.deleteRecursively()
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
        val previousOffset = appliedSubtitleOffsetMs
        appliedSubtitleOffsetMs = offsetMs
        SubtitlePreferenceUpdater.persistOffset(offsetMs)
        if (previousOffset == offsetMs) return
        val selectedFormat =
            player.currentTracks.groups.firstNotNullOfOrNull { group ->
                if (group.type != C.TRACK_TYPE_TEXT) return@firstNotNullOfOrNull null
                (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) }
            } ?: return
        if (Media3SubtitleFormatClassifier.isSsaFamilyTrack(selectedFormat)) return
        val item = player.currentMediaItem ?: return
        val track = getTracks(TrackType.SUBTITLE).firstOrNull { it.selected } ?: return
        // TextRenderer and SampleQueue discard consumed cues. A fresh source is required to
        // recover them when changing time in either direction, including while paused.
        replaceCurrentMediaSource(item, selectedFormat.id, track)
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
        subtitleSink = sink
        subtitleInput.setSink(sink)
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int
    ) {
        activateSubtitleInput(mediaItem)
    }

    private fun activateSubtitleInput(mediaItem: MediaItem?) {
        val input = mediaItem?.localConfiguration?.tag as? Media3SubtitleInput ?: return
        if (input === subtitleInput) return
        cancelSubtitleLoad()
        subtitleInput.setSink(null)
        subtitleInput = input
        subtitleInput.setSink(subtitleSink)
    }

    override fun onSubtitleBackendChanged(target: SubtitleRendererBackend) = Unit

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
            TrackType.SUBTITLE -> true
            else -> false
        }

    override fun supportAddTrack(track: VideoTrackBean): Boolean =
        if (track.type == TrackType.SUBTITLE) {
            runCatching { Media3ExternalSubtitle.mimeType(appContext, track) != null }.getOrDefault(false)
        } else {
            supportAddTrack(track.type)
        }

    override fun addTrack(track: VideoTrackBean): Boolean = addTrack(track) {}

    override fun addTrack(
        track: VideoTrackBean,
        onAdded: (VideoTrackBean) -> Unit
    ): Boolean {
        if (track.type != TrackType.SUBTITLE || !this::player.isInitialized) return false
        val item = player.currentMediaItem ?: return false
        val subtitleId = runCatching { Media3ExternalSubtitle.id(track) }.getOrNull() ?: return false
        val attached = item.localConfiguration?.subtitleConfigurations?.firstOrNull { it.id == subtitleId }
        val mimeType = attached?.mimeType ?: runCatching { Media3ExternalSubtitle.mimeType(appContext, track) }.getOrNull() ?: return false
        val sourceInput = subtitleInput
        cancelSubtitleLoad()
        val revision = subtitleLoadRevision
        subtitleLoadJob =
            subtitleScope.launch {
                try {
                    if (attached != null) {
                        subtitleLoadJob = null
                        pendingSubtitleId = subtitleId
                        pendingSubtitleAdded = onAdded
                        pendingSubtitleTrack = track
                        selectPendingSubtitle(player.currentTracks)
                        return@launch
                    }
                    val config =
                        withContext(Dispatchers.IO) { Media3ExternalSubtitle.prepare(appContext, track, mimeType, subtitleCacheDirectory) }
                    if (subtitleInput !== sourceInput || subtitleLoadRevision != revision) return@launch
                    val local = item.localConfiguration ?: return@launch
                    subtitleLoadJob = null
                    replaceCurrentMediaSource(
                        item.buildUpon().setSubtitleConfigurations(local.subtitleConfigurations + config).build(),
                        config.id,
                        track,
                        onAdded
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LogFacade.w(LogModule.PLAYER, "PlayerSubtitle", "External subtitle load failed: ${error.javaClass.simpleName}")
                    ToastCenter.showOriginalToast("加载字幕失败，请检查文件格式和内容")
                } finally {
                    if (subtitleLoadRevision == revision) subtitleLoadJob = null
                }
            }
        return true
    }

    private fun cancelSubtitleLoad() {
        subtitleLoadRevision++
        subtitleLoadJob?.cancel()
        subtitleLoadJob = null
        pendingSubtitleId = null
        pendingSubtitleAdded = null
        pendingSubtitleTrack = null
    }

    private fun replaceCurrentMediaSource(
        item: MediaItem,
        selectedId: String?,
        track: VideoTrackBean,
        onAdded: ((VideoTrackBean) -> Unit)? = null
    ) {
        val replacement = getMediaSource(item, subtitleInput.requestHeaders, subtitleInput.cacheEnabled)
        val selector = trackSelector as DefaultTrackSelector
        if (onAdded != null) {
            selector.parameters =
                selector.parameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build()
        }
        // Keep the remaining playlist and the current player's pause/speed/Surface state.
        val index = player.currentMediaItemIndex
        val position = player.contentPosition
        activateSubtitleInput(replacement.mediaItem)
        pendingSubtitleId = selectedId
        pendingSubtitleAdded = onAdded
        pendingSubtitleTrack = track
        mediaSource = replacement
        player.addMediaSource(index, replacement)
        player.seekTo(index, position)
        player.removeMediaItem(index + 1)
    }

    override fun getTracks(type: TrackType): List<VideoTrackBean> {
        val media3Type = getTrackType(type) ?: return emptyList()
        val nameProvider = if (type == TrackType.SUBTITLE) subtitleTrackNameProvider else trackNameProvider
        val tracks =
            player.currentTracks.groups.flatMapIndexed { groupIndex, group ->
                if (group.type != media3Type) {
                    return@flatMapIndexed emptyList()
                }
                mapByLength(group.length) { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    val name = nameProvider.getTrackName(format)
                    val selected = group.isTrackSelected(trackIndex)
                    val id = if (type == TrackType.SUBTITLE && format.id != null) "subtitle:${format.id}" else "$groupIndex-$trackIndex"
                    val subtitleMimeType = if (type == TrackType.SUBTITLE) Media3SubtitleFormatClassifier.originalMimeType(format) else null
                    VideoTrackBean.internal(id, name, type, selected, subtitleMimeType)
                }
            }
        if (type != TrackType.SUBTITLE) return tracks
        val nameCounts = tracks.groupingBy { it.name }.eachCount()
        return tracks.mapIndexed { index, track ->
            if (nameCounts.getValue(track.name) > 1) track.copy(name = "${track.name} · ${index + 1}") else track
        }
    }

    override fun selectTrack(track: VideoTrackBean) {
        if (track.type == TrackType.SUBTITLE) cancelSubtitleLoad()
        val media3Type = getTrackType(track.type) ?: return
        val selector = trackSelector as? DefaultTrackSelector ?: return
        val id = track.id ?: return
        val override =
            if (id.startsWith("subtitle:")) {
                val formatId = id.removePrefix("subtitle:")
                player.currentTracks.groups.firstNotNullOfOrNull { group ->
                    if (group.type != C.TRACK_TYPE_TEXT) {
                        null
                    } else {
                        (0 until group.length)
                            .firstOrNull {
                                group.getTrackFormat(it).id == formatId
                            }?.let { androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, it) }
                    }
                }
            } else {
                val indices = id.split("-")
                val groupIndex = indices.getOrNull(0)?.toIntOrNull() ?: return
                val trackIndex = indices.getOrNull(1)?.toIntOrNull() ?: return
                player.currentTracks.groups.getOrNull(groupIndex)?.let {
                    androidx.media3.common.TrackSelectionOverride(it.mediaTrackGroup, trackIndex)
                }
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
        if (type == TrackType.SUBTITLE) cancelSubtitleLoad()
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
        notifyPrepared()
        mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_VIDEO_RENDERING_START, 0)
        val path = currentDataSource
        if (!path.isNullOrBlank()) {
            runCatching {
                LocalProxy.setSeekEnabledIfServing(path, enabled = true)
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (isPreparing && state == Player.STATE_READY) {
            notifyPrepared()
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

    private fun notifyPrepared() {
        if (!isPreparing) return
        // First-frame and READY callbacks may arrive in either order. Publish readiness once,
        // before playback starts, so restoration cannot be skipped by the first-frame event.
        isPreparing = false
        mPlayerEventListener.onPrepared()
    }

    override fun onTracksChanged(tracks: Tracks) {
        selectPendingSubtitle(tracks)
        logTextTracks(tracks)
        mPlayerEventListener.onTracksChanged(TrackType.SUBTITLE)
    }

    private fun selectPendingSubtitle(tracks: Tracks) {
        pendingSubtitleId?.let { id ->
            val format =
                tracks.groups
                    .filter { it.type == C.TRACK_TYPE_TEXT }
                    .flatMap { group ->
                        (0 until group.length).map { group.getTrackFormat(it) }
                    }.firstOrNull { Media3ExternalSubtitle.matchesId(it.id, id) }
            if (format != null) {
                val added =
                    pendingSubtitleTrack?.copy(
                        id = "subtitle:${format.id}",
                        internal = true,
                        selected = true,
                        subtitleMimeType = Media3SubtitleFormatClassifier.originalMimeType(format)
                    )
                val callback = pendingSubtitleAdded
                cancelSubtitleLoad()
                if (added != null) {
                    selectTrack(added)
                    callback?.invoke(added)
                }
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        mPlayerEventListener.onError(error)
    }

    override fun onCues(cueGroup: CueGroup) {
        mPlayerEventListener.onSubtitleTextOutput(MixedSubtitle.fromCues(cueGroup.cues))
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
                val name = subtitleTrackNameProvider.getTrackName(format)
                val initDataSummary = formatInitializationDataSummary(format)
                LogFacade.d(
                    LogModule.PLAYER,
                    "PlayerSubtitle",
                    "media3 textTrack g=$groupIndex t=$trackIndex selected=${group.isTrackSelected(
                        trackIndex
                    )} supported=${group.isTrackSupported(
                        trackIndex
                    )} disabled=$textDisabled mime=${format.sampleMimeType} codecs=${format.codecs} label=${format.label} lang=${format.language} name=$name ssaLike=$isSsaTrack ssaCodec=$ssaCodec media3Cue=$isMedia3CueTrack initData=$initDataSummary",
                )
            }
        }

        if (!hasTextTracks) {
            LogFacade.d(LogModule.PLAYER, "PlayerSubtitle", "media3 textTracks: none disabled=$textDisabled")
        }
    }

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

    private companion object {
        private const val INIT_DATA_PREVIEW_BYTES = 64
        private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()
    }
}
