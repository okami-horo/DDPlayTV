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
import com.xyoye.player.kernel.subtitle.SubtitleFrameDriver
import com.xyoye.player.kernel.subtitle.SubtitleKernelBridge
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import com.xyoye.player.utils.DecodeType
import com.xyoye.player.utils.PlayerConstant
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

@androidx.annotation.OptIn(UnstableApi::class)
class MpvVideoPlayer(
    private val context: Context
) : AbstractVideoPlayer(),
    SubtitleKernelBridge {
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
    private var useEmbeddedSubtitlePipeline = false
    private val embeddedSubtitleSink = AtomicReference<EmbeddedSubtitleSink?>()
    private var embeddedSubtitleHeader: ByteArray? = null
    private var embeddedSubtitleHeaderSize: Point? = null
    private var mpvAssExtradata: ByteArray? = null
    private var pendingAssFullPayload: String? = null
    private var embeddedSubtitleReadOrder: Long = 0L
    private var lastSubtitlePayload: String? = null
    private var lastSubtitleAss = false
    private var lastSubtitleStartMs: Long? = null

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
        applyVideoOutputPreference()
    }

    override fun setDataSource(
        path: String,
        headers: Map<String, String>?
    ) {
        dataSource = path
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
                    }.getOrElse { "DanDanPlayForAndroid" }
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
        isPrepared = false
        isPreparing = false
        isPlaying = false
        decodeType = DecodeType.SW
        videoSize = Point(0, 0)
        initializationError = null
        resetEmbeddedSubtitlePipeline()
        mpvAssExtradata = null
    }

    override fun release() {
        stop()
        resetEmbeddedSubtitlePipeline()
        mpvAssExtradata = null
        nativeBridge.clearEventListener()
        nativeBridge.destroy()
        isPrepared = false
        isPreparing = false
        isPlaying = false
        decodeType = DecodeType.SW
    }

    override fun seekTo(timeMs: Long) {
        if (!isPrepared) return
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
        nativeBridge.setSubtitleDelay(offsetMs)
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

    override fun getTcpSpeed(): Long = 0

    override fun getDecodeType(): DecodeType {
        refreshDecodeTypeFromNative()
        return decodeType
    }

    override fun supportAddTrack(type: TrackType): Boolean = type == TrackType.AUDIO || type == TrackType.SUBTITLE

    override fun addTrack(track: VideoTrackBean): Boolean {
        val path = track.trackResource as? String ?: return false
        return nativeBridge.addExternalTrack(track.type, path)
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
            nativeBridge.deselectTrack(MpvNativeBridge.TRACK_TYPE_SUBTITLE)
            return
        }
        val ids = track.id?.split(":") ?: return
        val nativeType = ids.getOrNull(0)?.toIntOrNull() ?: return
        val trackId = ids.getOrNull(1)?.toIntOrNull() ?: return
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
                mPlayerEventListener.onPrepared()
            }
            is MpvNativeBridge.Event.RenderingStart -> {
                isPlaying = true
                refreshDecodeTypeFromNative()
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
            is MpvNativeBridge.Event.SubtitleHeader -> {
                handleSubtitleHeaderEvent(event)
            }
            is MpvNativeBridge.Event.Subtitle -> {
                handleSubtitleEvent(event)
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
        val configured = PlayerConfig.getMpvVideoOutput().orEmpty().trim()
        val safeOutput =
            when {
                configured.equals("gpu-next", ignoreCase = true) -> "gpu-next"
                configured.equals("mediacodec_embed", ignoreCase = true) -> "mediacodec_embed"
                else -> "gpu"
            }
        nativeBridge.setVideoOutput(safeOutput)
        updateEmbeddedSubtitlePreference(safeOutput)
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

    private fun updateEmbeddedSubtitlePreference(output: String) {
        val enable = output.equals("mediacodec_embed", ignoreCase = true)
        if (useEmbeddedSubtitlePipeline == enable) return
        useEmbeddedSubtitlePipeline = enable
        resetEmbeddedSubtitlePipeline()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun handleSubtitleHeaderEvent(event: MpvNativeBridge.Event.SubtitleHeader) {
        val raw = event.extradata
        if (raw.isBlank()) {
            if (mpvAssExtradata == null) {
                return
            }
            mpvAssExtradata = null
            if (useEmbeddedSubtitlePipeline) {
                embeddedSubtitleSink.get()?.onFlush()
                embeddedSubtitleHeader = null
                embeddedSubtitleHeaderSize = null
            }
            return
        }
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (mpvAssExtradata?.contentEquals(bytes) == true) {
            return
        }
        mpvAssExtradata = bytes
        if (!useEmbeddedSubtitlePipeline) {
            return
        }
        embeddedSubtitleReadOrder = 0L
        embeddedSubtitleHeader = null
        embeddedSubtitleHeaderSize = null
        lastSubtitlePayload = null
        lastSubtitleAss = false
        lastSubtitleStartMs = null
        val sink = embeddedSubtitleSink.get() ?: return
        sink.onFlush()
        sink.onFormat(bytes)
        embeddedSubtitleHeader = bytes
        pendingAssFullPayload?.let { pending ->
            if (pending.isNotBlank()) {
                pendingAssFullPayload = null
                appendAssFullPayload(sink, pending)
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun handleSubtitleEvent(event: MpvNativeBridge.Event.Subtitle) {
        if (!useEmbeddedSubtitlePipeline) return
        val sink = embeddedSubtitleSink.get() ?: return
        if (event.text.isBlank()) {
            if (lastSubtitlePayload == null) {
                return
            }
            sink.onFlush()
            lastSubtitlePayload = null
            lastSubtitleStartMs = null
            pendingAssFullPayload = null
            return
        }
        val startMs = event.startMs.coerceAtLeast(0L)
        if (!event.isAss && lastSubtitleAss && lastSubtitleStartMs == startMs) {
            return
        }
        val signature = buildSubtitleSignature(event, startMs)
        if (signature == lastSubtitlePayload && event.isAss == lastSubtitleAss) {
            return
        }
        val isAssFull = event.isAss && looksLikeAssFullEvent(event.text)
        if (isAssFull && mpvAssExtradata == null) {
            pendingAssFullPayload = event.text
            lastSubtitlePayload = signature
            lastSubtitleAss = true
            lastSubtitleStartMs = startMs
            return
        }
        ensureEmbeddedSubtitleHeader(sink)
        if (isAssFull) {
            pendingAssFullPayload = null
            appendAssFullPayload(sink, event.text)
        } else {
            val payload = buildAssChunk(event.text, event.isAss)
            val timeUs = startMs * 1000L
            val durationUs = event.durationMs?.takeIf { it > 0L }?.times(1000L)
            sink.onSample(payload.toByteArray(Charsets.UTF_8), timeUs, durationUs)
        }
        lastSubtitlePayload = signature
        lastSubtitleAss = event.isAss
        lastSubtitleStartMs = startMs
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun ensureEmbeddedSubtitleHeader(sink: EmbeddedSubtitleSink) {
        val mpvHeader = mpvAssExtradata
        if (mpvHeader != null) {
            if (embeddedSubtitleHeader == null || embeddedSubtitleHeader?.contentEquals(mpvHeader) != true) {
                embeddedSubtitleHeader = mpvHeader
                embeddedSubtitleHeaderSize = null
                sink.onFormat(mpvHeader)
            }
            pendingAssFullPayload?.let { pending ->
                if (pending.isNotBlank()) {
                    pendingAssFullPayload = null
                    appendAssFullPayload(sink, pending)
                }
            }
            return
        }
        val targetWidth =
            if (videoSize.x > 0) {
                videoSize.x
            } else {
                DEFAULT_ASS_PLAY_RES_X
            }
        val targetHeight =
            if (videoSize.y > 0) {
                videoSize.y
            } else {
                DEFAULT_ASS_PLAY_RES_Y
            }
        val cachedSize = embeddedSubtitleHeaderSize
        if (embeddedSubtitleHeader == null ||
            cachedSize == null ||
            cachedSize.x != targetWidth ||
            cachedSize.y != targetHeight
        ) {
            embeddedSubtitleHeaderSize = Point(targetWidth, targetHeight)
            embeddedSubtitleHeader = buildAssHeader(targetWidth, targetHeight)
            sink.onFormat(embeddedSubtitleHeader)
        }
    }

    private fun buildAssHeader(
        width: Int,
        height: Int
    ): ByteArray {
        val fontSize = PlayerInitializer.Subtitle.textSize.coerceAtLeast(MIN_ASS_FONT_SIZE)
        val header =
            """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: $width
            PlayResY: $height
            WrapStyle: 0
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,${SubtitleFontManager.DEFAULT_FONT_FAMILY},$fontSize,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,20,20,20,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            """.trimIndent()
        return header.toByteArray(Charsets.UTF_8)
    }

    private fun looksLikeAssFullEvent(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("Dialogue:", ignoreCase = true) || trimmed.startsWith("Comment:", ignoreCase = true)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun appendAssFullPayload(
        sink: EmbeddedSubtitleSink,
        payload: String
    ) {
        payload
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val chunk = parseAssFullDialogueLine(line) ?: return@forEach
                val timeUs = chunk.startMs * 1000L
                val durationUs = chunk.durationMs.takeIf { it > 0L }?.times(1000L)
                sink.onSample(chunk.payload.toByteArray(Charsets.UTF_8), timeUs, durationUs)
            }
    }

    private data class ParsedAssChunk(
        val payload: String,
        val startMs: Long,
        val durationMs: Long
    )

    private fun parseAssFullDialogueLine(line: String): ParsedAssChunk? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return null
        val kind = trimmed.substring(0, colon).trim()
        if (!kind.equals("Dialogue", ignoreCase = true)) {
            return null
        }
        val body = trimmed.substring(colon + 1).trimStart()
        val parts = body.split(",", limit = 10)
        if (parts.size < 10) return null
        val layer = parts[0].trim()
        val startMs = parseAssTimeMs(parts[1]) ?: return null
        val endMs = parseAssTimeMs(parts[2]) ?: return null
        val durationMs = (endMs - startMs).takeIf { it > 0L } ?: DEFAULT_ASS_DURATION_MS
        val style = parts[3].trim()
        val name = parts[4].trim()
        val marginL = parts[5].trim()
        val marginR = parts[6].trim()
        val marginV = parts[7].trim()
        val effect = parts[8].trim()
        val text = parts[9]
        val readOrder = embeddedSubtitleReadOrder++
        val payload =
            buildString {
                append(readOrder)
                append(',').append(layer)
                append(',').append(style)
                append(',').append(name)
                append(',').append(marginL)
                append(',').append(marginR)
                append(',').append(marginV)
                append(',').append(effect)
                append(',').append(text)
            }
        return ParsedAssChunk(payload, startMs, durationMs)
    }

    private fun parseAssTimeMs(value: String): Long? {
        val raw = value.trim()
        val parts = raw.split(":", limit = 3)
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val secParts = parts[2].split(".", limit = 2)
        if (secParts.size != 2) return null
        val seconds = secParts[0].toLongOrNull() ?: return null
        val csRaw = secParts[1].trim()
        if (csRaw.isEmpty()) return null
        val centiseconds = csRaw.padEnd(2, '0').take(2).toLongOrNull() ?: return null
        val totalSeconds = (hours * 3600L) + (minutes * 60L) + seconds
        return (totalSeconds * 1000L) + (centiseconds * 10L)
    }

    private fun buildSubtitleSignature(event: MpvNativeBridge.Event.Subtitle, startMs: Long): String {
        val durationMs = event.durationMs?.takeIf { it > 0L } ?: 0L
        return buildString {
            append(if (event.isAss) "A" else "T")
            append('\u0000').append(startMs)
            append('\u0000').append(durationMs)
            append('\u0000').append(event.text)
        }
    }

    private fun buildAssChunk(
        text: String,
        isAss: Boolean
    ): String {
        val safeText =
            if (isAss) {
                text
            } else {
                escapeAssText(text)
            }
        val readOrder = embeddedSubtitleReadOrder++
        return "$readOrder,0,Default,,0,0,0,,$safeText"
    }

    private fun escapeAssText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "\\N")
    }

    private fun formatAssTime(timeMs: Long): String {
        val safeMs = timeMs.coerceAtLeast(0L)
        val totalCs = safeMs / 10
        val cs = totalCs % 100
        val totalSec = totalCs / 100
        val sec = totalSec % 60
        val totalMin = totalSec / 60
        val min = totalMin % 60
        val hr = totalMin / 60
        return String.format(Locale.US, "%d:%02d:%02d.%02d", hr, min, sec, cs)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun resetEmbeddedSubtitlePipeline() {
        embeddedSubtitleSink.get()?.onFlush()
        embeddedSubtitleHeader = null
        embeddedSubtitleHeaderSize = null
        pendingAssFullPayload = null
        embeddedSubtitleReadOrder = 0L
        lastSubtitlePayload = null
        lastSubtitleAss = false
        lastSubtitleStartMs = null
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
                    "shader read failed: $shaderPath, reason=${error.message}"
                )
                return null
            }

        val hookRegex = Regex("^\\s*//!HOOK\\s+(\\S+)", RegexOption.IGNORE_CASE)
        val hooks =
            content.lineSequence()
                .mapNotNull { line -> hookRegex.find(line)?.groupValues?.getOrNull(1) }
                .toList()
        if (hooks.isNotEmpty()) {
            val matched = hooks.any { it.equals(stageToken, ignoreCase = true) }
            if (!matched) {
                LogFacade.w(
                    LogModule.PLAYER,
                    "MpvVideoPlayer",
                    "shader hook mismatch: requested=$stageToken, found=${hooks.joinToString(",")}"
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
                "shader write failed: ${wrapperFile.path}, reason=${error.message}"
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

    override fun canStartGpuSubtitlePipeline(): Boolean = useEmbeddedSubtitlePipeline

    override fun setEmbeddedSubtitleSink(sink: EmbeddedSubtitleSink?) {
        val previous = embeddedSubtitleSink.getAndSet(sink)
        if (previous === sink) {
            return
        }
        resetEmbeddedSubtitlePipeline()
    }

    override fun createFrameDriver(callback: SubtitleFrameDriver.Callback): SubtitleFrameDriver? = null

    companion object {
        private const val DEFAULT_ASS_PLAY_RES_X = 1920
        private const val DEFAULT_ASS_PLAY_RES_Y = 1080
        private const val DEFAULT_ASS_DURATION_MS = 5000L
        private const val MIN_ASS_FONT_SIZE = 10
    }
}

private class MpvPlaybackException(
    message: String,
    val code: Int? = null,
    val reason: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)
