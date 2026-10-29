package com.xyoye.player.kernel.impl.mpv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.PlaceholderSurface
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.data_component.enums.TrackType
import com.xyoye.mpv.PlezyMpvSession
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** App policy and main-thread events over Plezy's independently owned native session. */
@androidx.annotation.OptIn(UnstableApi::class)
class MpvNativeBridge {
    data class TrackInfo(
        val id: Int,
        val type: TrackType,
        val nativeType: Int,
        val title: String,
        val selected: Boolean,
        val codec: String? = null,
        val external: Boolean = false
    )

    sealed interface Event {
        object Prepared : Event

        object RenderingStart : Event

        object Completed : Event

        data class VideoSize(
            val width: Int,
            val height: Int
        ) : Event

        data class Buffering(
            val started: Boolean
        ) : Event

        data class Error(
            val code: Int?,
            val reason: Int?,
            val message: String?
        ) : Event

        data class LogMessage(
            val level: Int,
            val message: String?
        ) : Event

        data class SubtitleSid(
            val value: String?
        ) : Event
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(Event) -> Unit>()
    private val listenerLock = Any()
    private val worker =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "ddplay-mpv").apply { isDaemon = true }
        }
    private var session: PlezyMpvSession? = null
    private var appContext: Context? = null
    private var placeholder: PlaceholderSurface? = null
    private var surfaceGeneration = 0L
    private var subtitleSurfaceGeneration = 0L
    private var videoTarget: Surface? = null
    private var subtitleSurface: Surface? = null
    private val externalSubtitleRevision = AtomicLong()
    private var externalSubtitleId: Int? = null
    private var externalSubtitleOwnerRevision = 0L
    private var surfaceAttached = false
    private var pausedSurfacePosition: Double? = null
    private var activeSource: Long? = null
    private var activeRevision = 0L
    private var hasVideo = false
    private var videoStarted = false

    @Volatile private var released = false

    @Volatile private var eventGeneration = 0L

    @Volatile private var sourceRevision = 0L

    @Volatile private var errorMessage: String? = null

    @Volatile private var positionMs = 0L

    @Volatile private var durationMs = 0L

    @Volatile private var dimensions = 0 to 0

    @Volatile private var decoder: String? = null

    @Volatile private var paused = true

    @Volatile private var tracks: List<TrackInfo> = emptyList()

    @Volatile var isAvailable = false
        private set

    val availabilityReason: String? get() = errorMessage

    fun lastError(): String? = errorMessage

    fun currentPosition(): Long = positionMs

    fun duration(): Long = durationMs

    fun videoSize(): Pair<Int, Int> = dimensions

    fun hwdecCurrent(): String? = decoder

    fun isPaused(): Boolean = paused

    fun listTracks(): List<TrackInfo> = tracks

    fun initialize(
        context: Context,
        onReady: (Boolean) -> Unit
    ) {
        appContext = context.applicationContext
        worker.execute {
            if (released) return@execute
            val result =
                runCatching {
                    session =
                        PlezyMpvSession(
                            context.applicationContext,
                            DEFAULT_OPTIONS,
                            object : PlezyMpvSession.Listener {
                                override fun onEvent(
                                    type: Int,
                                    sourceId: Long?,
                                    code: Int,
                                    reason: Int,
                                    message: String?
                                ) {
                                    enqueue {
                                        if (type == 2) {
                                            dispatchEvent(Event.LogMessage(code, message))
                                        } else if (sourceId != null && sourceId == activeSource) {
                                            handleEvent(it, type, code, reason, message)
                                        }
                                    }
                                }

                                override fun onProperty(
                                    name: String,
                                    sourceId: Long?,
                                    value: Any?
                                ) {
                                    enqueue {
                                        if (sourceId != null && sourceId == activeSource) handleProperty(it, name, value)
                                    }
                                }
                            }
                        )
                    val player = checkNotNull(session)
                    mapOf(
                        "time-pos" to "Double",
                        "duration" to "Double",
                        "pause" to "Flag",
                        "paused-for-cache" to "Flag",
                        "dwidth" to "Int64",
                        "dheight" to "Int64",
                        "hwdec-current" to "String",
                        "track-list" to "None",
                        "sid" to "String"
                    ).forEach { (name, format) -> player.observe(name, format) }
                    isAvailable = !released
                }
            result.exceptionOrNull()?.let {
                errorMessage = "Failed to initialize Plezy mpv: ${it.message}"
                Log.e(TAG, errorMessage, it)
                runCatching { session?.close() }
                session = null
            }
            mainHandler.post { if (!released) onReady(result.isSuccess) }
        }
    }

    fun setSurface(
        surface: Surface,
        onResult: (Boolean) -> Unit
    ) {
        enqueue(onResult = onResult) {
            check(surface.isValid) { "mpv video Surface is invalid" }
            val pausedPosition = pausedSurfacePosition ?: pausedSeekablePosition(it)
            it.attachSurfaces(surface, subtitleSurface, ++surfaceGeneration, subtitleSurfaceGeneration)
            videoTarget = surface
            surfaceAttached = true
            pausedSurfacePosition = null
            if (pausedPosition != null && it.getFlag("pause") == true && it.getFlag("seekable") == true) {
                it.command("seek", pausedPosition.toString(), "absolute+exact")
            }
        }
    }

    /** SurfaceHolder destruction must wait until native has retired the old window. */
    fun detachSurface(): Boolean =
        call(false) {
            if (surfaceAttached) {
                pausedSurfacePosition = pausedSeekablePosition(it)
                val target = placeholder ?: PlaceholderSurface.newInstance(checkNotNull(appContext), false).also { placeholder = it }
                it.attachSurfaces(target, null, ++surfaceGeneration, subtitleSurfaceGeneration)
                videoTarget = target
                surfaceAttached = false
            }
            true
        }

    fun setSubtitleSurface(
        surface: Surface,
        onResult: (Boolean) -> Unit
    ) {
        enqueue(onResult = onResult) {
            check(surface.isValid) { "mpv subtitle Surface is invalid" }
            val generation = ++subtitleSurfaceGeneration
            if (surfaceAttached) {
                it.attachSurfaces(checkNotNull(videoTarget), surface, surfaceGeneration, generation)
            }
            subtitleSurface = surface
        }
    }

    /** Retire the OSD consumer before Android destroys its Surface. */
    fun detachSubtitleSurface(): Boolean =
        call(false) {
            videoTarget?.let { video ->
                it.attachSurfaces(video, null, surfaceGeneration, subtitleSurfaceGeneration)
            }
            subtitleSurface = null
            true
        }

    fun setSubtitleSurfaceSize(
        width: Int,
        height: Int
    ) {
        if (width > 0 && height > 0) {
            // The overlay follows the actual laid-out video rectangle, including aspect changes.
            setOption("vo-mediacodec-video-rect", "$width,$height,0,0,$width,$height")
        }
    }

    private fun pausedSeekablePosition(player: PlezyMpvSession): Double? =
        if (activeSource != null && player.getFlag("pause") == true && player.getFlag("seekable") == true) {
            player.getDouble("time-pos")?.takeIf { it.isFinite() && it >= 0 }
        } else {
            null
        }

    fun setDataSource(
        path: String,
        headers: Map<String, String>,
        onResult: (Boolean) -> Unit
    ) {
        val revision = ++sourceRevision
        externalSubtitleRevision.incrementAndGet()
        enqueue(onResult = { if (revision == sourceRevision) onResult(it) }) {
            externalSubtitleId = null
            videoStarted = false
            pausedSurfacePosition = null
            positionMs = 0
            durationMs = 0
            tracks = emptyList()
            it.command("change-list", "http-header-fields", "clr", "")
            headers.entries.sortedBy { it.key.lowercase() }.forEach { header ->
                it.command("change-list", "http-header-fields", "append", "${header.key}: ${header.value}")
            }
            activeSource = it.command("loadfile", nativePath(path), "replace")
            check(activeSource != null) { "mpv loadfile did not return a source ID" }
            activeRevision = revision
        }
    }

    fun play() =
        enqueue {
            pausedSurfacePosition = null
            it.setProperty("pause", false)
            paused = false
        }

    fun pause() =
        enqueue {
            it.setProperty("pause", true)
            paused = true
        }

    fun stop() {
        sourceRevision++
        externalSubtitleRevision.incrementAndGet()
        enqueue {
            externalSubtitleId = null
            activeSource = null
            pausedSurfacePosition = null
            videoStarted = false
            it.command("stop")
            paused = true
            positionMs = 0
        }
    }

    fun seek(positionMs: Long) =
        enqueue {
            it.command("seek", (positionMs / 1000.0).toString(), "absolute+exact")
            if (!surfaceAttached && it.getFlag("pause") == true) pausedSurfacePosition = positionMs / 1000.0
            Unit
        }

    fun setSpeed(speed: Float) = enqueue { it.setProperty("speed", speed.toDouble()) }

    fun setVolume(volume: Float) = enqueue { it.setProperty("volume", volume.coerceIn(0f, 1f) * 100.0) }

    fun setLooping(looping: Boolean) = setOption("loop-file", if (looping) "inf" else "no")

    fun setSubtitleDelay(offsetMs: Long) = enqueue { it.setProperty("sub-delay", offsetMs / 1000.0) }

    fun setSubtitleVisible(visible: Boolean) = enqueue { it.setProperty("sub-visibility", visible) }

    fun setSubtitleScale(offsetPercent: Int) = enqueue { it.setProperty("sub-scale", 1.0 + offsetPercent.coerceIn(-50, 400) / 100.0) }

    fun setSubtitlePosition(offsetPercent: Int) = enqueue { it.setProperty("sub-pos", (100 - offsetPercent.coerceIn(-30, 30)).toDouble()) }

    fun setUserAgent(userAgent: String) = setOption("user-agent", userAgent)

    fun setForceSeekable(enabled: Boolean) = setOption("force-seekable", if (enabled) "yes" else "no")

    fun setAudioOutput(value: String) = setOption("ao", value)

    fun setVideoSync(value: String) = setOption("video-sync", value)

    fun applyDefaultOptions(logLevel: LogLevel?) =
        enqueue {
            it.setLogLevel(
                when (logLevel) {
                    null -> "no"
                    LogLevel.ERROR -> "error"
                    LogLevel.WARN -> "warn"
                    LogLevel.INFO -> "info"
                    LogLevel.DEBUG -> "v"
                }
            )
        }

    fun setSubtitleFonts(
        fontsDir: String,
        defaultFontFamily: String
    ) {
        if (fontsDir.isNotBlank()) setOption("sub-fonts-dir", fontsDir)
        if (defaultFontFamily.isNotBlank()) setOption("sub-font", defaultFontFamily)
        setOption("sub-font-provider", "none")
    }

    fun setSurfaceSize(
        width: Int,
        height: Int
    ) {
        if (width > 0 && height > 0) setOption("android-surface-size", "${width}x$height")
    }

    fun selectTrack(
        nativeType: Int,
        trackId: Int
    ): Boolean {
        if (nativeType == TRACK_TYPE_SUBTITLE) externalSubtitleRevision.incrementAndGet()
        return call(false) {
            it.setProperty(if (nativeType == TRACK_TYPE_AUDIO) "aid" else "sid", trackId.toString())
            refreshTracks(it)
            true
        }
    }

    fun deselectTrack(nativeType: Int): Boolean {
        if (nativeType == TRACK_TYPE_SUBTITLE) externalSubtitleRevision.incrementAndGet()
        return call(false) {
            it.setProperty(if (nativeType == TRACK_TYPE_AUDIO) "aid" else "sid", "no")
            refreshTracks(it)
            true
        }
    }

    suspend fun loadExternalSubtitle(path: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val revision = externalSubtitleRevision.incrementAndGet()
            continuation.invokeOnCancellation {
                externalSubtitleRevision.compareAndSet(revision, revision + 1)
                enqueue {
                    if (externalSubtitleOwnerRevision == revision) removeExternalSubtitle(it)
                }
            }
            var loaded = false
            enqueue(onResult = { success ->
                if (continuation.isActive) continuation.resume(success && loaded && revision == externalSubtitleRevision.get())
            }) { player ->
                if (revision != externalSubtitleRevision.get()) return@enqueue
                removeExternalSubtitle(player)
                val previousIds = tracks.filter { it.type == TrackType.SUBTITLE }.map { it.id }.toSet()
                player.command("sub-add", nativePath(path), "auto")
                refreshTracks(player)
                val added =
                    tracks.firstOrNull { it.type == TrackType.SUBTITLE && it.external && it.id !in previousIds }
                        ?: error("mpv did not load the external subtitle")
                externalSubtitleId = added.id
                externalSubtitleOwnerRevision = revision
                if (revision != externalSubtitleRevision.get()) {
                    removeExternalSubtitle(player)
                    return@enqueue
                }
                try {
                    player.setProperty("sid", added.id.toString())
                    refreshTracks(player)
                    loaded = true
                } catch (error: Exception) {
                    removeExternalSubtitle(player)
                    throw error
                }
            }
        }

    fun clearExternalSubtitle() {
        externalSubtitleRevision.incrementAndGet()
        enqueue { removeExternalSubtitle(it) }
    }

    private fun removeExternalSubtitle(player: PlezyMpvSession) {
        val id = externalSubtitleId ?: return
        externalSubtitleId = null
        player.command("sub-remove", id.toString())
        refreshTracks(player)
    }

    fun addExternalTrack(
        type: TrackType,
        path: String
    ): Boolean =
        call(false) {
            val command =
                when (type) {
                    TrackType.AUDIO -> "audio-add"
                    TrackType.SUBTITLE -> "sub-add"
                    else -> return@call false
                }
            it.command(command, nativePath(path), "select")
            refreshTracks(it)
            true
        }

    fun setEventListener(listener: (Event) -> Unit) {
        eventGeneration++
        synchronized(listenerLock) {
            listeners.clear()
            listeners.add(listener)
        }
    }

    fun addEventListener(listener: (Event) -> Unit) {
        synchronized(listenerLock) { listeners.add(listener) }
    }

    fun removeEventListener(listener: (Event) -> Unit) {
        synchronized(listenerLock) { listeners.remove(listener) }
    }

    fun clearEventListener() {
        eventGeneration++
        synchronized(listenerLock) { listeners.clear() }
    }

    fun destroy() {
        synchronized(worker) {
            if (released) return
            released = true
            isAvailable = false
            clearEventListener()
            worker.execute {
                try {
                    session?.close()
                } catch (error: Exception) {
                    Log.w(TAG, "mpv close failed", error)
                } finally {
                    session = null
                    placeholder?.release()
                    placeholder = null
                }
            }
            worker.shutdown()
        }
    }

    internal fun dispatchEvent(
        event: Event,
        revision: Long = sourceRevision
    ) {
        val generation = eventGeneration
        val targets = synchronized(listenerLock) { listeners.toList() }
        mainHandler.post {
            if (released || generation != eventGeneration || revision != sourceRevision) return@post
            targets.forEach { listener ->
                if (synchronized(listenerLock) { listener in listeners }) runCatching { listener(event) }
            }
        }
    }

    private fun handleEvent(
        player: PlezyMpvSession,
        type: Int,
        code: Int,
        reason: Int,
        message: String?
    ) {
        when (type) {
            8 -> {
                refreshTracks(player)
                dispatchEvent(Event.Prepared, activeRevision)
            }
            21 -> {
                decoder = player.getString("hwdec-current")
                paused = player.getFlag("pause") ?: paused
                positionMs = ((player.getDouble("time-pos") ?: 0.0) * 1000).toLong()
                if (validateVideo(player)) dispatchEvent(Event.RenderingStart, activeRevision)
            }
            7 ->
                when (reason) {
                    0 -> dispatchEvent(Event.Completed, activeRevision)
                    4 -> dispatchEvent(Event.Error(code, reason, message), activeRevision)
                }
        }
    }

    private fun handleProperty(
        player: PlezyMpvSession,
        name: String,
        value: Any?
    ) {
        val event =
            when (name) {
                "time-pos" -> {
                    positionMs = ((value as? Number)?.toDouble()?.times(1000) ?: 0.0).toLong()
                    null
                }
                "duration" -> {
                    durationMs = ((value as? Number)?.toDouble()?.times(1000) ?: 0.0).toLong()
                    null
                }
                "pause" -> {
                    paused = value as? Boolean ?: true
                    null
                }
                "paused-for-cache" -> Event.Buffering(value == true)
                "track-list" -> {
                    refreshTracks(player)
                    null
                }
                "dwidth", "dheight" -> {
                    dimensions = (player.getInt("dwidth") ?: 0) to (player.getInt("dheight") ?: 0)
                    Event.VideoSize(dimensions.first, dimensions.second)
                }
                "hwdec-current" -> {
                    decoder = value as? String
                    if (videoStarted) validateVideo(player)
                    null
                }
                "sid" -> {
                    refreshTracks(player)
                    Event.SubtitleSid(value as? String)
                }
                else -> null
            }
        if (event != null) dispatchEvent(event, activeRevision)
    }

    private fun validateVideo(player: PlezyMpvSession): Boolean {
        if (!surfaceAttached || !hasVideo || player.getFlag("playback-abort") == true) return true
        if (decoder == "mediacodec") {
            videoStarted = true
            return true
        }
        videoStarted = false
        errorMessage = "mpv MediaCodec video decoding failed. Switch to Media3 for this file."
        dispatchEvent(Event.Error(PlezyMpvSession.VIDEO_OUTPUT_ERROR, 4, errorMessage), activeRevision)
        player.command("stop")
        return false
    }

    private fun refreshTracks(player: PlezyMpvSession) {
        hasVideo = false
        tracks =
            buildList {
                repeat(player.getInt("track-list/count") ?: 0) { index ->
                    val key = "track-list/$index"
                    val type =
                        when (player.getString("$key/type")) {
                            "audio" -> TrackType.AUDIO
                            "sub" -> TrackType.SUBTITLE
                            "video" -> {
                                if (player.getFlag("$key/image") != true && player.getFlag("$key/albumart") != true) hasVideo = true
                                return@repeat
                            }
                            else -> return@repeat
                        }
                    val id = player.getInt("$key/id") ?: return@repeat
                    val title =
                        player.getString("$key/title")?.takeIf { it.isNotBlank() }
                            ?: player.getString("$key/lang")?.takeIf { it.isNotBlank() } ?: "Track $id"
                    add(
                        TrackInfo(
                            id,
                            type,
                            if (type == TrackType.AUDIO) TRACK_TYPE_AUDIO else TRACK_TYPE_SUBTITLE,
                            title,
                            player.getFlag("$key/selected") == true,
                            player.getString("$key/codec"),
                            player.getFlag("$key/external") == true
                        )
                    )
                }
            }
    }

    private fun setOption(
        name: String,
        value: String
    ) = enqueue { it.setProperty(name, value) }

    private fun enqueue(
        onResult: ((Boolean) -> Unit)? = null,
        action: (PlezyMpvSession) -> Unit
    ) {
        synchronized(worker) {
            if (released) {
                onResult?.let { mainHandler.post { it(false) } }
                return
            }
            worker.execute {
                if (released) {
                    onResult?.let { mainHandler.post { it(false) } }
                    return@execute
                }
                val result = runCatching { action(checkNotNull(session) { errorMessage ?: "mpv is not initialized" }) }
                result.exceptionOrNull()?.let {
                    errorMessage = it.message
                    Log.w(TAG, "mpv operation failed", it)
                    if (onResult == null) dispatchEvent(Event.Error(null, null, errorMessage))
                }
                onResult?.let { callback -> mainHandler.post { callback(!released && result.isSuccess) } }
            }
        }
    }

    private fun <T> call(
        fallback: T,
        action: (PlezyMpvSession) -> T
    ): T {
        if (released) return fallback
        return try {
            worker.submit<T> { action(checkNotNull(session)) }.get(1500, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            errorMessage = error.cause?.message ?: error.message
            Log.w(TAG, "mpv synchronous operation failed", error)
            fallback
        }
    }

    companion object {
        private const val TAG = "MpvNativeBridge"
        const val VIDEO_OUTPUT = "mediacodec"
        const val TRACK_TYPE_AUDIO = 1
        const val TRACK_TYPE_SUBTITLE = 2

        internal fun nativePath(path: String): String {
            val uri = Uri.parse(path)
            return if (uri.scheme == "file" && (uri.authority.isNullOrEmpty() || uri.authority == "localhost")) {
                uri.path ?: path
            } else {
                path
            }
        }

        private val DEFAULT_OPTIONS =
            mapOf(
                "config" to "no",
                "terminal" to "no",
                "pause" to "yes",
                "idle" to "yes",
                "profile" to "fast",
                "hwdec" to "mediacodec",
                "vo" to VIDEO_OUTPUT,
                "vd-lavc-software-fallback" to "no",
                "stop-playback-on-init-failure" to "yes",
                "ao" to "audiotrack,opensles",
                "force-window" to "no",
                "sub-visibility" to "yes",
                "sub-auto" to "no",
                "sub-ass-override" to "scale",
                "embeddedfonts" to "yes",
                "osd-level" to "0"
            )
    }
}
