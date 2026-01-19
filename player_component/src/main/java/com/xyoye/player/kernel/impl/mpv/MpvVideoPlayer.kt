package com.xyoye.player.kernel.impl.mpv

import android.content.Context
import android.graphics.Point
import android.net.Uri
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.LogSystem
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.storage.file.helper.HttpPlayServer
import com.xyoye.common_component.subtitle.SubtitleFontManager
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.kernel.subtitle.SubtitleKernelBridge
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import com.xyoye.player.utils.DecodeType
import com.xyoye.player.utils.PlayerConstant
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class MpvVideoPlayer(
    private val context: Context
) : AbstractVideoPlayer(),
    SubtitleKernelBridge {
    private data class PendingExternalTrack(
        val type: TrackType,
        val path: String,
    )

    private val appContext: Context = context.applicationContext
    private val nativeBridge = MpvNativeBridge()
    private var dataSource: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var userAgent: String? = null
    private var proxySeekEnabled: Boolean = false
    private var pendingSeekMs: Long? = null
    private var videoSize = Point(0, 0)
    private var isPrepared = false
    private var isPreparing = false
    private var isPlaying = false
    private var decodeType: DecodeType = DecodeType.SW
    private var playbackSpeed = PlayerInitializer.Player.videoSpeed
    private var looping = PlayerInitializer.isLooping
    private var initializationError: Exception? = null
    private var anime4kMode: Int = Anime4kShaderManager.MODE_OFF
    private val embeddedSubtitleBridge = MpvEmbeddedSubtitleBridge()
    private val pendingExternalTracks = mutableListOf<PendingExternalTrack>()

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
        MpvNativeBridge.registerAndroidAppContext(context)
        nativeBridge.setEventListener(::onNativeEvent)
        if (!nativeBridge.isAvailable) {
            val reason = nativeBridge.availabilityReason ?: "libmpv.so missing or failed to link"
            failInitialization(reason)
            return
        }
        if (!nativeBridge.ensureCreated()) {
            val reason = nativeBridge.lastError() ?: "Failed to initialize mpv native session"
            failInitialization(reason)
            return
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
        applyHwdecPriority()
        applyAudioOutputPreference()
        applyVideoSyncPreference()
        applyVideoOutputPreference()
    }

    override fun setDataSource(
        path: String,
        headers: Map<String, String>?
    ) {
        setAnime4kMode(Anime4kShaderManager.MODE_OFF)
        dataSource = path
        pendingExternalTracks.clear()
        runCatching {
            val playServer = HttpPlayServer.getInstance()
            if (playServer.isServingUrl(path)) {
                playServer.setSeekEnabled(false)
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
                userAgentEntry != null -> userAgentEntry.value
                shouldInjectUserAgent ->
                    runCatching {
                        Util.getUserAgent(appContext, appContext.applicationInfo.name ?: appContext.packageName)
                    }.getOrElse { "DDPlayTV" }
                else -> null
            }

        this.headers =
            if (userAgentEntry != null) {
                originalHeaders.filterKeys { !it.equals(userAgentEntry.key, ignoreCase = true) }
            } else {
                originalHeaders
            }
    }

    override fun setSurface(surface: Surface) {
        if (!nativeBridge.isAvailable) return
        applyVideoOutputPreference()
        nativeBridge.setSurface(surface)
        setAnime4kMode(anime4kMode)
    }

    fun setSurfaceSize(
        width: Int,
        height: Int
    ) {
        if (!nativeBridge.isAvailable) return
        nativeBridge.setSurfaceSize(width, height)
    }

    /**
     * Append a custom shader to mpv's `glsl-shaders` list.
     *
     * If [stage] is provided:
     * - When the shader file already contains `//!HOOK`, it must include the same hook.
     * - Otherwise the shader source is wrapped with `//!HOOK <stage>` and `//!BIND HOOKED`
     *   and written to app cache before being appended.
     *
     * Supported hook stages (case-insensitive, libplacebo/mpv):
     * - RGB: input plane with RGB values
     * - LUMA: input plane with luma (Y)
     * - CHROMA: input chroma plane(s)
     * - ALPHA: input alpha plane
     * - XYZ: input plane with XYZ values
     * - CHROMA_SCALED: chroma upscaled to luma size
     * - ALPHA_SCALED: alpha upscaled to luma size
     * - NATIVE: merged input planes before color conversion
     * - MAIN: after RGB conversion, before linearization/scaling
     * - MAINPRESUB: mpv compatibility hook, treated as MAIN by libplacebo
     * - LINEAR: after conversion to linear light
     * - SIGMOID: after conversion to sigmoidized light (for upscaling)
     * - PREKERNEL: right before main scaling kernel
     * - POSTKERNEL: right after main scaling kernel
     * - SCALED: after scaling (linear or nonlinear light)
     * - PREOUTPUT: after conversion to output colorspace, before blending
     * - OUTPUT: after blending, before dithering/final output
     *
     * Note: some hooks may never fire depending on input (e.g. RGB input skips LUMA/CHROMA).
     *
     * Example: addShader("/sdcard/shaders/filmgrain.hook", "LUMA")
     */
    fun addShader(
        shaderPath: String,
        stage: String? = null
    ): Boolean {
        if (!nativeBridge.isAvailable) return false
        val resolvedPath = resolveShaderPath(shaderPath, stage) ?: return false
        return nativeBridge.addShader(resolvedPath)
    }

    fun getAnime4kMode(): Int = anime4kMode

    fun setAnime4kMode(mode: Int) {
        val safeMode =
            when (mode) {
                Anime4kShaderManager.MODE_PERFORMANCE -> Anime4kShaderManager.MODE_PERFORMANCE
                Anime4kShaderManager.MODE_QUALITY -> Anime4kShaderManager.MODE_QUALITY
                else -> Anime4kShaderManager.MODE_OFF
            }
        anime4kMode = safeMode

        if (!nativeBridge.isAvailable) {
            return
        }

        val outputSupported = MpvOptions.isAnime4kSupportedVideoOutput(PlayerConfig.getMpvVideoOutput())

        if (safeMode == Anime4kShaderManager.MODE_OFF || outputSupported) {
            if (!nativeBridge.clearShaders()) {
                LogFacade.w(
                    LogModule.PLAYER,
                    "MpvVideoPlayer",
                    "clearShaders failed: ${nativeBridge.lastError().orEmpty()}",
                )
            }
        }

        if (safeMode == Anime4kShaderManager.MODE_OFF) {
            return
        }

        if (!outputSupported) {
            anime4kMode = Anime4kShaderManager.MODE_OFF
            return
        }

        val shaderPaths = Anime4kShaderManager.resolveShaderPaths(appContext, safeMode)
        if (shaderPaths.isEmpty()) {
            LogFacade.w(LogModule.PLAYER, "MpvVideoPlayer", "Anime4K shader paths unavailable")
            return
        }

        val shaderList = shaderPaths.joinToString(separator = ":")
        if (nativeBridge.setShaders(shaderList)) {
            return
        }

        LogFacade.w(
            LogModule.PLAYER,
            "MpvVideoPlayer",
            "setShaders failed, fallback to append: ${nativeBridge.lastError().orEmpty()}",
        )

        shaderPaths.forEach { path ->
            if (!nativeBridge.addShader(path)) {
                LogFacade.w(
                    LogModule.PLAYER,
                    "MpvVideoPlayer",
                    "addShader failed: path=$path reason=${nativeBridge.lastError().orEmpty()}",
                )
            }
        }
    }

    override fun prepareAsync() {
        if (initializationError != null) {
            mPlayerEventListener.onError(initializationError)
            return
        }
        val path = dataSource
        if (path.isNullOrEmpty()) {
            mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_URL_EMPTY, 0)
            return
        }
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
        var dataSourceError: Exception? = null
        val success =
            try {
                userAgent?.let { nativeBridge.setUserAgent(it) }
                runCatching {
                    val playServer = HttpPlayServer.getInstance()
                    nativeBridge.setForceSeekable(playServer.isServingUrl(path))
                }.getOrNull()
                nativeBridge.setDataSource(path, headers)
            } catch (e: Exception) {
                dataSourceError = e
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "MpvVideoPlayer",
                    "prepareAsync",
                    "Failed to set mpv data source",
                )
                false
            }
        if (!success) {
            isPreparing = false
            val reason =
                nativeBridge.lastError()
                    ?: dataSourceError?.message
                    ?: "mpv bridge rejected data source"
            failInitialization(reason, cause = dataSourceError)
            return
        }
        nativeBridge.play()
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
        if (!isPrepared) return
        nativeBridge.stop()
        isPlaying = false
    }

    override fun reset() {
        nativeBridge.stop()
        dataSource = null
        headers = emptyMap()
        pendingExternalTracks.clear()
        isPrepared = false
        isPreparing = false
        isPlaying = false
        decodeType = DecodeType.SW
        videoSize = Point(0, 0)
        initializationError = null
    }

    override fun release() {
        embeddedSubtitleBridge.release()
        pendingExternalTracks.clear()
        clearPlayerEventListener()
        stop()
        nativeBridge.clearEventListener()
        nativeBridge.destroy()
        isPrepared = false
        isPreparing = false
        isPlaying = false
        decodeType = DecodeType.SW
    }

    override fun seekTo(timeMs: Long) {
        if (!isPrepared) return
        if (canStartGpuSubtitlePipeline()) {
            embeddedSubtitleBridge.resetForTimelineChange()
        }
        if (!proxySeekEnabled) {
            val path = dataSource
            if (!path.isNullOrEmpty()) {
                val isLocalProxy = runCatching { HttpPlayServer.getInstance().isServingUrl(path) }.getOrDefault(false)
                if (isLocalProxy) {
                    pendingSeekMs = timeMs
                    return
                }
            }
        }
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
        if (canStartGpuSubtitlePipeline()) {
            embeddedSubtitleBridge.resetForTimelineChange()
        }
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

    override fun supportAddTrack(type: TrackType): Boolean = type == TrackType.AUDIO || type == TrackType.SUBTITLE

    override fun addTrack(track: VideoTrackBean): Boolean {
        val path = track.trackResource as? String ?: return false
        if (path.isBlank()) return false
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
        val tracks =
            nativeBridge
                .listTracks()
                .filter { it.type == type }
                .map {
                    VideoTrackBean.internal(
                        id = "${it.nativeType}:${it.id}",
                        name = it.title,
                        type = type,
                        selected = it.selected,
                    )
                }
        if (type != TrackType.SUBTITLE) {
            return tracks
        }
        val hasSelected = tracks.any { it.selected }
        val disableTrack = VideoTrackBean.disable(type, selected = !hasSelected)
        return listOf(disableTrack) + tracks
    }

    override fun selectTrack(track: VideoTrackBean) {
        if (!isPrepared) return
        if (track.disable) {
            if (canStartGpuSubtitlePipeline()) {
                embeddedSubtitleBridge.resetForTimelineChange()
            }
            nativeBridge.deselectTrack(MpvNativeBridge.TRACK_TYPE_SUBTITLE)
            return
        }
        val ids = track.id?.split(":") ?: return
        val nativeType = ids.getOrNull(0)?.toIntOrNull() ?: return
        val trackId = ids.getOrNull(1)?.toIntOrNull() ?: return
        if (track.type == TrackType.SUBTITLE && canStartGpuSubtitlePipeline()) {
            embeddedSubtitleBridge.resetForTimelineChange()
        }
        nativeBridge.selectTrack(nativeType, trackId)
    }

    override fun deselectTrack(type: TrackType) {
        if (!isPrepared) return
        val nativeType =
            when (type) {
                TrackType.SUBTITLE -> MpvNativeBridge.TRACK_TYPE_SUBTITLE
                TrackType.AUDIO -> MpvNativeBridge.TRACK_TYPE_AUDIO
                else -> return
            }
        if (type == TrackType.SUBTITLE && canStartGpuSubtitlePipeline()) {
            embeddedSubtitleBridge.resetForTimelineChange()
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
                    2 -> LogFacade.i(LogModule.PLAYER, "mpv_bridge", message)
                    else -> LogFacade.d(LogModule.PLAYER, "mpv_bridge", message)
                }
            }
            is MpvNativeBridge.Event.Buffering -> {
                mPlayerEventListener.onInfo(
                    if (event.started) PlayerConstant.MEDIA_INFO_BUFFERING_START else PlayerConstant.MEDIA_INFO_BUFFERING_END,
                    0,
                )
            }
            is MpvNativeBridge.Event.Completed -> {
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
                isPrepared = true
                isPreparing = false
                flushPendingExternalTracks()
                mPlayerEventListener.onPrepared()
            }
            is MpvNativeBridge.Event.RenderingStart -> {
                isPlaying = true
                refreshDecodeTypeFromNative()
                flushPendingExternalTracks()
                val path = dataSource
                if (!path.isNullOrEmpty()) {
                    runCatching {
                        val playServer = HttpPlayServer.getInstance()
                        if (playServer.isServingUrl(path)) {
                            playServer.setSeekEnabled(true)
                            proxySeekEnabled = true
                            pendingSeekMs?.let { pending ->
                                pendingSeekMs = null
                                nativeBridge.seek(pending)
                            }
                        }
                    }
                }
                mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_VIDEO_RENDERING_START, 0)
            }
            is MpvNativeBridge.Event.VideoSize -> {
                videoSize = Point(event.width, event.height)
                mPlayerEventListener.onVideoSizeChange(event.width, event.height)
            }
            is MpvNativeBridge.Event.SubtitleAssExtradata -> {
                if (canStartGpuSubtitlePipeline()) {
                    embeddedSubtitleBridge.onAssExtradata(event.value)
                }
            }
            is MpvNativeBridge.Event.SubtitleAssFull -> {
                if (canStartGpuSubtitlePipeline()) {
                    embeddedSubtitleBridge.onAssFull(event.value)
                }
            }
            is MpvNativeBridge.Event.SubtitleSid -> {
                if (canStartGpuSubtitlePipeline()) {
                    embeddedSubtitleBridge.onSid(event.value)
                }
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

    private fun applyVideoOutputPreference() {
        nativeBridge.setVideoOutput(MpvOptions.resolveVideoOutput(PlayerConfig.getMpvVideoOutput()))
    }

    private fun applyHwdecPriority() {
        val configured = PlayerConfig.getMpvHwdecPriority().orEmpty().trim()
        val preferCopy = configured.equals("mediacodec-copy", ignoreCase = true)
        val hwdec =
            if (preferCopy) {
                "mediacodec-copy,mediacodec"
            } else {
                "mediacodec,mediacodec-copy"
            }
        nativeBridge.setHwdecPriority(hwdec)
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
    }

    private fun applyVideoSyncPreference() {
        val configured = PlayerConfig.getMpvVideoSync().orEmpty().trim()
        if (configured.equals("default", ignoreCase = true) || configured.isEmpty()) {
            return
        }
        nativeBridge.setVideoSync(configured)
    }

    private fun resolveShaderPath(
        shaderPath: String,
        stage: String?
    ): String? {
        if (shaderPath.isBlank()) return null
        val requestedStage = stage?.trim().orEmpty()
        if (requestedStage.isEmpty()) return shaderPath

        val stageToken = requestedStage.uppercase()
        val shaderFile = File(shaderPath)
        if (!shaderFile.isFile) {
            LogFacade.w(LogModule.PLAYER, "MpvVideoPlayer", "shader file missing: $shaderPath")
            return null
        }
        val content =
            runCatching { shaderFile.readText() }.getOrElse { error ->
                LogFacade.w(
                    LogModule.PLAYER,
                    "MpvVideoPlayer",
                    "shader read failed: $shaderPath, reason=${error.message}",
                )
                return null
            }

        val hookRegex = Regex("^\\s*//!HOOK\\s+(\\S+)", RegexOption.IGNORE_CASE)
        val hooks =
            content
                .lineSequence()
                .mapNotNull { line -> hookRegex.find(line)?.groupValues?.getOrNull(1) }
                .toList()
        if (hooks.isNotEmpty()) {
            val matched = hooks.any { it.equals(stageToken, ignoreCase = true) }
            if (!matched) {
                LogFacade.w(
                    LogModule.PLAYER,
                    "MpvVideoPlayer",
                    "shader hook mismatch: requested=$stageToken, found=${hooks.joinToString(",")}",
                )
                return null
            }
            return shaderFile.absolutePath
        }

        val cacheDir = File(appContext.cacheDir, "mpv-shaders")
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            LogFacade.w(LogModule.PLAYER, "MpvVideoPlayer", "shader cache dir create failed: ${cacheDir.path}")
            return null
        }
        val stageTag = stageToken.replace(Regex("[^A-Z0-9_-]"), "_").lowercase()
        val cacheKey = "${shaderFile.absolutePath}:${shaderFile.lastModified()}:$stageToken"
        val cacheHash = Integer.toHexString(cacheKey.hashCode())
        val wrapperFile = File(cacheDir, "hook_${stageTag}_$cacheHash.hook")
        val header =
            buildString {
                append("//!HOOK ").append(stageToken).append('\n')
                append("//!BIND HOOKED").append('\n')
            }
        val output = header + content
        runCatching { wrapperFile.writeText(output) }.getOrElse { error ->
            LogFacade.w(
                LogModule.PLAYER,
                "MpvVideoPlayer",
                "shader write failed: ${wrapperFile.path}, reason=${error.message}",
            )
            return null
        }
        return wrapperFile.absolutePath
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

    override fun canStartGpuSubtitlePipeline(): Boolean =
        MpvOptions.resolveVideoOutput(PlayerConfig.getMpvVideoOutput()) == MpvOptions.VO_MEDIACODEC_EMBED

    override fun setEmbeddedSubtitleSink(sink: EmbeddedSubtitleSink?) {
        embeddedSubtitleBridge.setSink(sink)
    }
}

private class MpvPlaybackException(
    message: String,
    val code: Int? = null,
    val reason: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)
