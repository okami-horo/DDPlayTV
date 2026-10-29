package com.xyoye.player.kernel.impl.mpv

import android.content.Context
import android.graphics.Point
import android.net.Uri
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.log.BuglyReporter
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.LogSystem
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.storage.file.helper.LocalProxy
import com.xyoye.common_component.subtitle.SubtitleFontManager
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.utils.DecodeType
import com.xyoye.player.utils.PlayerConstant
import com.xyoye.player_component.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(UnstableApi::class)
class MpvVideoPlayer(
    private val context: Context
) : AbstractVideoPlayer() {
    private data class PendingExternalTrack(
        val type: TrackType,
        val path: String
    )

    private val appContext: Context = context.applicationContext
    private val nativeBridge = MpvNativeBridge()
    internal val subtitleBridge: MpvNativeBridge get() = nativeBridge
    private var boundSurface: Surface? = null
    private var surfaceRevision = 0L
    private var surfaceReady = false
    private var dataSource: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var userAgent: String? = null
    private var proxySeekEnabled: Boolean = false
    private var pendingSeekMs: Long? = null
    private var awaitingSeek = false
    private var videoSize = Point(0, 0)
    private var isPrepared = false
    private var isPreparing = false
    private var preparePendingSurface = false
    private var isPlaying = false
    private var decodeType: DecodeType = DecodeType.SW
    private var playbackSpeed = PlayerInitializer.Player.videoSpeed
    private var looping = PlayerInitializer.isLooping
    private var initializationError: Exception? = null
    private val pendingExternalTracks = mutableListOf<PendingExternalTrack>()
    private var playbackSessionId: String = "-"
    private var buglyLogEnabledForSession: Boolean = false
    private var userAgentMode: String = "none"

    private fun failInitialization(
        message: String,
        code: Int? = null,
        cause: Throwable? = null
    ): Exception {
        val error = MpvPlaybackException(message, code = code, cause = cause)
        initializationError = error
        mPlayerEventListener.onError(error)
        return error
    }

    override fun initPlayer() {
        initializationError = null
        nativeBridge.setEventListener(::onNativeEvent)
        nativeBridge.initialize(context) { success ->
            if (!success) failInitialization(nativeBridge.lastError() ?: "Failed to initialize mpv native session")
        }
        setOptions()
    }

    override fun setOptions() {
        val logLevel =
            if (PlayerInitializer.isPrintLog) {
                LogSystem.getRuntimeState().activePolicy.defaultLevel
            } else {
                null
            }
        nativeBridge.applyDefaultOptions(logLevel)
        SubtitleFontManager.ensureDefaultFont(appContext)
        val fontsDir = SubtitleFontManager.getFontsDirectoryPath(appContext).orEmpty()
        nativeBridge.setSubtitleFonts(fontsDir, SubtitleFontManager.DEFAULT_FONT_FAMILY)
        nativeBridge.setLooping(looping)
        nativeBridge.setSpeed(playbackSpeed)
        BuglyReporter.updateContext(
            mapOf(
                "play.kernel" to "mpv",
                "play.looping" to looping.toString(),
                "play.speed" to playbackSpeed.toString(),
                "play.vo" to MpvNativeBridge.VIDEO_OUTPUT,
                "play.hwdec" to "mediacodec",
                "play.sub_backend" to "MPV_NATIVE",
            ),
        )
        applyAudioOutputPreference()
        applyVideoSyncPreference()
    }

    override fun setDataSource(
        path: String,
        headers: Map<String, String>?
    ) {
        dataSource = path
        pendingExternalTracks.clear()
        playbackSessionId = createPlaybackSessionId()
        buglyLogEnabledForSession = shouldEnableBuglyLogForSession()
        runCatching {
            if (LocalProxy.setSeekEnabledIfServing(path, enabled = false)) {
                proxySeekEnabled = false
                pendingSeekMs = null
            }
        }
        val originalHeaders = headers.orEmpty()
        val userAgentEntry = originalHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
        val shouldInjectUserAgent =
            runCatching {
                val scheme = Uri.parse(path).scheme
                scheme == "http" || scheme == "https"
            }.getOrDefault(false)

        userAgent =
            when {
                userAgentEntry != null -> {
                    userAgentMode = "provided"
                    userAgentEntry.value
                }
                shouldInjectUserAgent ->
                    runCatching {
                        userAgentMode = "injected"
                        Util.getUserAgent(appContext, appContext.applicationInfo.name ?: appContext.packageName)
                    }.getOrElse { "DDPlayTV" }
                else -> {
                    userAgentMode = "none"
                    null
                }
            }

        this.headers =
            if (userAgentEntry != null) {
                originalHeaders.filterKeys { !it.equals(userAgentEntry.key, ignoreCase = true) }
            } else {
                originalHeaders
            }

        BuglyReporter.updateContext(
            mapOf(
                "play.kernel" to "mpv",
                "play.session" to playbackSessionId,
                "play.src" to path,
                "play.src_hash" to Integer.toHexString(path.hashCode()),
                "play.src_scheme" to (runCatching { Uri.parse(path).scheme }.getOrNull() ?: ""),
                "play.headers_n" to this.headers.size.toString(),
                "play.ua_mode" to userAgentMode,
                "play.sub_backend" to "MPV_NATIVE",
            ),
        )
        for (i in 0 until BUGLY_BREADCRUMB_MAX) {
            BuglyReporter.updateContext("bc.$i", "-")
        }
        recordBuglyBreadcrumb(
            message = "setDataSource",
            context =
                mapOf(
                    "src_hash" to Integer.toHexString(path.hashCode()),
                    "headers_n" to this.headers.size.toString(),
                    "ua_mode" to userAgentMode,
                ),
        )
    }

    override fun setSurface(surface: Surface) {
        if (!surface.isValid) return
        boundSurface = surface
        surfaceReady = false
        val revision = ++surfaceRevision
        nativeBridge.setSurface(surface) { success ->
            if (revision != surfaceRevision || boundSurface !== surface) return@setSurface
            if (!success) {
                nativeBridge.stop()
                failInitialization(nativeBridge.lastError() ?: "Failed to bind mpv video surface")
                return@setSurface
            }
            surfaceReady = true
            BuglyReporter.updateContext("surf.set", "1")
            recordBuglyBreadcrumb("setSurface", mapOf("vo" to MpvNativeBridge.VIDEO_OUTPUT))
            if (preparePendingSurface) prepareAsync()
        }
    }

    fun clearSurface(surface: Surface) {
        if (boundSurface !== surface) return
        surfaceRevision++
        boundSurface = null
        surfaceReady = false
        if (!nativeBridge.detachSurface()) {
            nativeBridge.stop()
            failInitialization(nativeBridge.lastError() ?: "Failed to detach mpv video surface")
        }
    }

    fun setSurfaceSize(
        width: Int,
        height: Int
    ) {
        nativeBridge.setSurfaceSize(width, height)
        BuglyReporter.updateContext("surf.size", "${width}x$height")
        recordBuglyBreadcrumb(
            message = "setSurfaceSize",
            context =
                mapOf(
                    "size" to "${width}x$height",
                ),
        )
    }

    override fun prepareAsync() {
        if (initializationError != null) {
            recordBuglyBreadcrumb(
                message = "prepareAsync skipped (initError)",
                context = mapOf("reason" to (initializationError?.message.orEmpty())),
            )
            mPlayerEventListener.onError(initializationError)
            return
        }
        val path = dataSource
        if (path.isNullOrEmpty()) {
            recordBuglyBreadcrumb(message = "prepareAsync skipped (empty dataSource)")
            mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_URL_EMPTY, 0)
            return
        }
        if (!surfaceReady || boundSurface?.isValid != true) {
            preparePendingSurface = true
            isPreparing = true
            return
        }
        preparePendingSurface = false
        BuglyReporter.updateContext("mpv.event", "PrepareStart")
        recordBuglyBreadcrumb(
            message = "prepareAsync",
            context = mapOf("src_hash" to Integer.toHexString(path.hashCode())),
        )
        if (PlayerInitializer.isPrintLog) {
            val uri = runCatching { Uri.parse(path) }.getOrNull()
            val queryKeys = runCatching { uri?.queryParameterNames?.sorted()?.joinToString(",") }.getOrNull().orEmpty()
            LogFacade.d(
                LogModule.PLAYER,
                "MpvVideoPlayer",
                "mpv prepareAsync dataSource scheme=${uri?.scheme.orEmpty()} host=${uri?.host.orEmpty()} port=${uri?.port ?: -1} path=${uri?.encodedPath.orEmpty()} queryKeys=$queryKeys hash=${path.hashCode()} headers=${headers.size}",
            )
        }
        isPreparing = true
        userAgent?.let { nativeBridge.setUserAgent(it) }
        nativeBridge.setForceSeekable(LocalProxy.isServingUrl(path))
        nativeBridge.setDataSource(path, headers) { success ->
            if (!success) {
                nativeBridge.stop()
                isPreparing = false
                isPrepared = false
                isPlaying = false
                val reason = nativeBridge.lastError() ?: "mpv rejected data source"
                BuglyReporter.updateContext(mapOf("mpv.event" to "SetDataSourceFailed", "mpv.last_error" to reason))
                recordBuglyBreadcrumb("setDataSource failed", mapOf("reason" to reason))
                failInitialization(reason)
            } else {
                nativeBridge.play()
            }
        }
    }

    override fun start() {
        if (!isPrepared) return
        nativeBridge.play()
        isPlaying = true
    }

    override fun pause() {
        if (!isPrepared) return
        nativeBridge.pause()
        isPlaying = false
    }

    override fun stop() {
        preparePendingSurface = false
        if (!isPrepared) return
        nativeBridge.stop()
        isPlaying = false
    }

    override fun reset() {
        awaitingSeek = false
        nativeBridge.stop()
        dataSource = null
        headers = emptyMap()
        pendingExternalTracks.clear()
        isPrepared = false
        isPreparing = false
        preparePendingSurface = false
        isPlaying = false
        decodeType = DecodeType.SW
        videoSize = Point(0, 0)
        initializationError = null
        recordBuglyBreadcrumb(message = "reset")
    }

    override fun release() {
        pendingExternalTracks.clear()
        clearPlayerEventListener()
        stop()
        nativeBridge.clearEventListener()
        if (boundSurface != null) nativeBridge.detachSurface()
        surfaceRevision++
        surfaceReady = false
        nativeBridge.destroy()
        boundSurface = null
        isPrepared = false
        isPreparing = false
        isPlaying = false
        decodeType = DecodeType.SW
        recordBuglyBreadcrumb(message = "release")
    }

    override fun seekTo(timeMs: Long) {
        if (!isPrepared) return
        recordBuglyBreadcrumb(
            message = "seekTo",
            context = mapOf("pos_ms" to timeMs.toString()),
        )
        if (!proxySeekEnabled) {
            val path = dataSource
            if (!path.isNullOrEmpty()) {
                val isLocalProxy = runCatching { LocalProxy.isServingUrl(path) }.getOrDefault(false)
                if (isLocalProxy) {
                    pendingSeekMs = timeMs
                    return
                }
            }
        }
        awaitingSeek = true
        nativeBridge.seek(timeMs)
    }

    override fun setSpeed(speed: Float) {
        playbackSpeed = speed
        if (!isPrepared) return
        nativeBridge.setSpeed(speed)
    }

    override fun setVolume(
        leftVolume: Float,
        rightVolume: Float
    ) {
        val average = (leftVolume + rightVolume) / 2f
        if (!isPrepared) return
        nativeBridge.setVolume(average)
    }

    override fun setLooping(isLooping: Boolean) {
        looping = isLooping
        if (!isPrepared) return
        nativeBridge.setLooping(isLooping)
    }

    override fun setSubtitleOffset(offsetMs: Long) {
        if (!isPrepared) return
        nativeBridge.setSubtitleDelay(-offsetMs)
    }

    override fun isPlaying(): Boolean = isPrepared && isPlaying

    override fun getCurrentPosition(): Long {
        if (!isPrepared) return 0
        return nativeBridge.currentPosition()
    }

    override fun getDuration(): Long {
        if (!isPrepared) return 0
        return nativeBridge.duration()
    }

    override fun getSpeed(): Float = playbackSpeed

    override fun getVideoSize(): Point {
        if (videoSize.x == 0 || videoSize.y == 0) {
            val (width, height) = nativeBridge.videoSize()
            videoSize = Point(width, height)
        }
        return videoSize
    }

    override fun getBufferedPercentage(): Int = 0

    override fun supportBufferedPercentage(): Boolean = false

    override fun getTcpSpeed(): Long = 0

    override fun getDecodeType(): DecodeType {
        refreshDecodeTypeFromNative()
        return decodeType
    }

    override fun supportAddTrack(type: TrackType): Boolean =
        when (type) {
            TrackType.AUDIO -> true
            TrackType.SUBTITLE -> false
            else -> false
        }

    override fun addTrack(track: VideoTrackBean): Boolean {
        if (track.type == TrackType.SUBTITLE) {
            LogFacade.i(
                LogModule.PLAYER,
                "MpvVideoPlayer",
                "external subtitle routed to subtitle controller: ${track.trackResource}",
            )
            recordBuglyBreadcrumb(
                message = "addTrack routed",
                context =
                    mapOf(
                        "type" to track.type.name,
                    ),
            )
            return false
        }
        val path = track.trackResource as? String ?: return false
        if (path.isBlank()) return false
        recordBuglyBreadcrumb(
            message = "addTrack",
            context =
                mapOf(
                    "type" to track.type.name,
                    "path_hash" to Integer.toHexString(path.hashCode()),
                ),
        )
        val added = nativeBridge.addExternalTrack(track.type, path)
        if (added) {
            pendingExternalTracks.removeAll { it.type == track.type }
            return true
        }
        if (isPrepared) {
            LogFacade.w(
                LogModule.PLAYER,
                "MpvVideoPlayer",
                "addExternalTrack failed: type=${track.type} path=$path reason=${nativeBridge.lastError().orEmpty()}",
            )
            return false
        }
        pendingExternalTracks.removeAll { it.type == track.type }
        pendingExternalTracks.add(PendingExternalTrack(track.type, path))
        LogFacade.d(
            LogModule.PLAYER,
            "MpvVideoPlayer",
            "addExternalTrack deferred until prepared: type=${track.type} path=$path",
        )
        return true
    }

    override fun getTracks(type: TrackType): List<VideoTrackBean> {
        if (!isPrepared) return emptyList()
        return nativeBridge
            .listTracks()
            .filter { it.type == type && (type != TrackType.SUBTITLE || !it.external) }
            .map {
                VideoTrackBean.internal(
                    id = "${it.nativeType}:${it.id}",
                    name = it.title,
                    type = type,
                    selected = it.selected,
                )
            }
    }

    override fun selectTrack(track: VideoTrackBean) {
        if (!isPrepared) return
        if (track.disable) {
            recordBuglyBreadcrumb(
                message = "selectTrack disable",
                context = mapOf("type" to track.type.name),
            )
            deselectTrack(track.type)
            return
        }
        val ids = track.id?.split(":") ?: return
        val nativeType = ids.getOrNull(0)?.toIntOrNull() ?: return
        val trackId = ids.getOrNull(1)?.toIntOrNull() ?: return
        recordBuglyBreadcrumb(
            message = "selectTrack",
            context =
                mapOf(
                    "type" to track.type.name,
                    "native_type" to nativeType.toString(),
                    "id" to trackId.toString(),
                ),
        )
        nativeBridge.selectTrack(nativeType, trackId)
    }

    override fun deselectTrack(type: TrackType) {
        val nativeType =
            when (type) {
                TrackType.SUBTITLE -> MpvNativeBridge.TRACK_TYPE_SUBTITLE
                TrackType.AUDIO -> MpvNativeBridge.TRACK_TYPE_AUDIO
                else -> return
            }
        nativeBridge.deselectTrack(nativeType)
    }

    private fun onNativeEvent(event: MpvNativeBridge.Event) {
        when (event) {
            is MpvNativeBridge.Event.LogMessage -> {
                if (!PlayerInitializer.isPrintLog) return
                val message =
                    event.message
                        ?.let(::sanitizeMpvLog)
                        ?.trimEnd()
                if (message.isNullOrEmpty()) return
                when (event.level) {
                    5, 4 -> LogFacade.e(LogModule.PLAYER, "mpv_bridge", message)
                    3 -> LogFacade.w(LogModule.PLAYER, "mpv_bridge", message)
                    else -> Unit
                }
            }
            is MpvNativeBridge.Event.Buffering -> {
                BuglyReporter.updateContext("mpv.event", if (event.started) "BufferingStart" else "BufferingEnd")
                recordBuglyBreadcrumb(message = if (event.started) "buffering start" else "buffering end")
                mPlayerEventListener.onInfo(
                    if (event.started) PlayerConstant.MEDIA_INFO_BUFFERING_START else PlayerConstant.MEDIA_INFO_BUFFERING_END,
                    0,
                )
            }
            is MpvNativeBridge.Event.Completed -> {
                BuglyReporter.updateContext("mpv.event", "Completed")
                recordBuglyBreadcrumb(message = "completed")
                isPlaying = false
                isPrepared = false
                isPreparing = false
                mPlayerEventListener.onCompletion()
            }
            is MpvNativeBridge.Event.Error -> {
                val rawMessage = event.message
                val fallbackMessage = nativeBridge.lastError()
                val readable =
                    formatNativeError(
                        rawMessage ?: fallbackMessage,
                        event.code,
                        event.reason,
                    )
                val error = MpvPlaybackException(readable, code = event.code, reason = event.reason)
                LogFacade.e(
                    LogModule.PLAYER,
                    "MpvVideoPlayer",
                    "mpv onNativeError: $readable",
                    context =
                        mapOf(
                            "dataSourceHash" to (dataSource?.hashCode()?.toString() ?: "null"),
                            "code" to (event.code?.toString() ?: "null"),
                            "reason" to (event.reason?.toString() ?: "null"),
                            "rawMessage" to (rawMessage ?: "null"),
                            "lastError" to (fallbackMessage ?: "null"),
                        ),
                    throwable = error,
                )
                BuglyReporter.updateContext(
                    mapOf(
                        "mpv.event" to "Error",
                        "mpv.err" to readable,
                        "mpv.err_raw" to (rawMessage ?: ""),
                        "mpv.last_error" to (fallbackMessage ?: ""),
                        "play.session" to playbackSessionId,
                        "play.src_hash" to (dataSource?.let { Integer.toHexString(it.hashCode()) } ?: ""),
                        "play.vo" to MpvNativeBridge.VIDEO_OUTPUT,
                        "play.sub_backend" to "MPV_NATIVE",
                    ),
                )
                recordBuglyBreadcrumb(
                    message = "native error",
                    context =
                        mapOf(
                            "code" to (event.code?.toString() ?: ""),
                            "reason" to (event.reason?.toString() ?: ""),
                        ),
                )
                initializationError = error
                isPrepared = false
                isPreparing = false
                isPlaying = false
                ErrorReportHelper.postCatchedExceptionWithContext(
                    error,
                    "MpvVideoPlayer",
                    "onNativeError",
                    readable,
                )
                mPlayerEventListener.onError(error)
            }
            is MpvNativeBridge.Event.Prepared -> {
                BuglyReporter.updateContext("mpv.event", "Prepared")
                recordBuglyBreadcrumb(message = "prepared")
                isPrepared = true
                isPreparing = false
                flushPendingExternalTracks()
                mPlayerEventListener.onPrepared()
            }
            is MpvNativeBridge.Event.RenderingStart -> {
                // The bridge emits this for MPV_EVENT_PLAYBACK_RESTART, including completed seeks.
                if (awaitingSeek) {
                    awaitingSeek = false
                    mPlayerEventListener.onSeekComplete()
                }
                BuglyReporter.updateContext("mpv.event", "RenderingStart")
                recordBuglyBreadcrumb(message = "rendering start")
                isPlaying = !nativeBridge.isPaused()
                refreshDecodeTypeFromNative()
                flushPendingExternalTracks()
                val path = dataSource
                if (!path.isNullOrEmpty()) {
                    runCatching {
                        if (LocalProxy.setSeekEnabledIfServing(path, enabled = true)) {
                            proxySeekEnabled = true
                            pendingSeekMs?.let { pending ->
                                pendingSeekMs = null
                                awaitingSeek = true
                                nativeBridge.seek(pending)
                            }
                        }
                    }
                }
                mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_VIDEO_RENDERING_START, 0)
            }
            is MpvNativeBridge.Event.VideoSize -> {
                videoSize = Point(event.width, event.height)
                BuglyReporter.updateContext("video.size", "${event.width}x${event.height}")
                recordBuglyBreadcrumb(
                    message = "video size",
                    context = mapOf("size" to "${event.width}x${event.height}"),
                )
                mPlayerEventListener.onVideoSizeChange(event.width, event.height)
            }
            is MpvNativeBridge.Event.SubtitleSid -> {
                BuglyReporter.updateContext("mpv.sid", event.value.orEmpty())
                recordBuglyBreadcrumb(
                    message = "subtitle sid",
                    context = mapOf("sid" to event.value.orEmpty()),
                )
            }
        }
    }

    private fun flushPendingExternalTracks() {
        if (!nativeBridge.isAvailable) return
        if (!isPrepared) return
        if (pendingExternalTracks.isEmpty()) return

        val pending = pendingExternalTracks.toList()
        pendingExternalTracks.clear()
        pending.forEach { track ->
            val added = nativeBridge.addExternalTrack(track.type, track.path)
            if (!added) {
                LogFacade.w(
                    LogModule.PLAYER,
                    "MpvVideoPlayer",
                    "flushExternalTrack failed: type=${track.type} path=${track.path} reason=${nativeBridge.lastError().orEmpty()}",
                )
            }
        }
    }

    private fun refreshDecodeTypeFromNative() {
        if (!nativeBridge.isAvailable) {
            decodeType = DecodeType.SW
            return
        }
        val current = nativeBridge.hwdecCurrent()?.trim().orEmpty()
        decodeType =
            if (current.isEmpty() || current.equals("no", ignoreCase = true) || current.equals("none", ignoreCase = true)) {
                DecodeType.SW
            } else {
                DecodeType.HW
            }
    }

    private fun applyAudioOutputPreference() {
        val configured = PlayerConfig.getMpvAudioOutput().orEmpty().trim()
        val ao =
            when {
                configured.equals("default", ignoreCase = true) || configured.isEmpty() -> null
                configured.equals("opensles", ignoreCase = true) -> "opensles,audiotrack"
                configured.equals("audiotrack", ignoreCase = true) -> "audiotrack,opensles"
                else -> configured
            }
        ao?.let(nativeBridge::setAudioOutput)
        BuglyReporter.updateContext("play.ao", ao ?: "default")
    }

    private fun applyVideoSyncPreference() {
        val configured = PlayerConfig.getMpvVideoSync().orEmpty().trim()
        if (configured.equals("default", ignoreCase = true) || configured.isEmpty()) {
            BuglyReporter.updateContext("play.video_sync", "default")
            return
        }
        nativeBridge.setVideoSync(configured)
        BuglyReporter.updateContext("play.video_sync", configured)
    }

    private fun sanitizeMpvLog(message: String): String {
        var result = message
        // Redact common credential headers.
        result = result.replace(Regex("(?i)(authorization\\s*:\\s*)([^\\s]+)"), "$1<redacted>")
        result = result.replace(Regex("(?i)(cookie\\s*:\\s*)(.+)"), "$1<redacted>")
        // Redact sensitive query parameters (keep key, hide value).
        result = result.replace(Regex("(?i)([?&](sign|sig|token|auth|key|secret|passwd|password)=)[^&#\\s]+"), "$1<redacted>")
        return result
    }

    private fun formatNativeError(
        message: String?,
        code: Int?,
        reason: Int?
    ): String {
        val reasonLabel =
            when (reason) {
                0 -> "eof"
                2 -> "stop"
                3 -> "quit"
                4 -> "error"
                5 -> "redirect"
                else -> null
            }
        val details =
            buildList {
                if (!message.isNullOrBlank()) add(message)
                if (code != null) add("code=$code")
                if (reasonLabel != null) {
                    add("reason=$reasonLabel")
                } else if (reason != null) {
                    add("reason=$reason")
                }
            }
        return details.joinToString(" | ").ifEmpty { "mpv playback error" }
    }

    private fun recordBuglyBreadcrumb(
        message: String,
        context: Map<String, String> = emptyMap()
    ) {
        val line = BuglyReporter.recordBreadcrumb("mpv", message, context)
        if (buglyLogEnabledForSession) {
            BuglyReporter.logI("mpv", line)
        }
    }

    private fun createPlaybackSessionId(): String {
        val now = System.currentTimeMillis()
        val counter = PLAYBACK_SESSION_COUNTER.incrementAndGet()
        return now.toString(36) + "-" + counter.toString(36)
    }

    private fun shouldEnableBuglyLogForSession(): Boolean {
        if (BuildConfig.DEBUG) return true
        val seq = BUGLY_LOG_SAMPLE_COUNTER.incrementAndGet()
        return seq % BUGLY_LOG_SAMPLE_RATE == 0
    }

    companion object {
        private val PLAYBACK_SESSION_COUNTER = AtomicInteger(0)
        private val BUGLY_LOG_SAMPLE_COUNTER = AtomicInteger(0)
        private const val BUGLY_LOG_SAMPLE_RATE = 20
        private const val BUGLY_BREADCRUMB_MAX = 6
    }
}

private class MpvPlaybackException(
    message: String,
    val code: Int? = null,
    val reason: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)
