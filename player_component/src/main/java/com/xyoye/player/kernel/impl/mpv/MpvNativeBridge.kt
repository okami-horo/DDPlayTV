package com.xyoye.player.kernel.impl.mpv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.Keep
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.data_component.enums.TrackType

private const val TAG = "MpvNativeBridge"

/**
 * Lightweight JNI wrapper for libmpv.
 *
 * The native side currently falls back to no-op behavior when libmpv
 * is not packaged, so callers should check [isAvailable] before invoking
 * player commands to surface a readable error to the UI.
 */
class MpvNativeBridge {
    data class TrackInfo(
        val id: Int,
        val type: TrackType,
        val nativeType: Int,
        val title: String,
        val selected: Boolean
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
    }

    private var nativeHandle: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listenerLock = Any()
    private val eventListeners = LinkedHashSet<(Event) -> Unit>()

    @Volatile
    private var eventLoopStarted = false

    val availabilityReason: String?
        get() = availabilityMessage

    val isAvailable: Boolean
        get() = nativeLoaded && nativeLinked

    fun lastError(): String? {
        if (!nativeLoaded) {
            return availabilityMessage
        }
        return nativeLastError()
    }

    fun ensureCreated(): Boolean {
        if (!isAvailable) return false
        if (nativeHandle != 0L) return true
        nativeHandle = nativeCreate()
        if (nativeHandle == 0L) {
            Log.w(TAG, "nativeCreate returned null handle")
            return false
        }
        val hasListeners =
            synchronized(listenerLock) {
                eventListeners.isNotEmpty()
            }
        if (hasListeners) {
            startEventLoop()
        }
        return true
    }

    fun destroy() {
        if (nativeHandle != 0L) {
            if (eventLoopStarted) {
                nativeStopEventLoop(nativeHandle)
            }
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
        eventLoopStarted = false
        synchronized(listenerLock) {
            eventListeners.clear()
        }
    }

    fun setSurface(surface: Surface?) {
        if (nativeHandle != 0L) {
            nativeSetSurface(nativeHandle, surface)
        }
    }

    fun setDataSource(
        path: String,
        headers: Map<String, String>
    ): Boolean {
        if (nativeHandle == 0L) return false
        val headerArray =
            headers.entries
                .sortedBy { it.key.lowercase() }
                .map { "${it.key}: ${it.value}" }
                .toTypedArray()
        val success = nativeSetDataSource(nativeHandle, path, headerArray)
        if (!success) {
            Log.w(TAG, "mpv setDataSource failed: ${lastError().orEmpty()}")
        }
        return success
    }

    fun play() {
        if (nativeHandle != 0L) nativePlay(nativeHandle)
    }

    fun pause() {
        if (nativeHandle != 0L) nativePause(nativeHandle)
    }

    fun stop() {
        if (nativeHandle != 0L) nativeStop(nativeHandle)
    }

    fun seek(positionMs: Long) {
        if (nativeHandle != 0L) nativeSeek(nativeHandle, positionMs)
    }

    fun setSpeed(speed: Float) {
        if (nativeHandle != 0L) nativeSetSpeed(nativeHandle, speed)
    }

    fun setVolume(volume: Float) {
        if (nativeHandle != 0L) nativeSetVolume(nativeHandle, volume)
    }

    fun setLooping(looping: Boolean) {
        if (nativeHandle != 0L) nativeSetLooping(nativeHandle, looping)
    }

    fun setSubtitleDelay(offsetMs: Long) {
        if (nativeHandle != 0L) nativeSetSubtitleDelay(nativeHandle, offsetMs)
    }

    fun applyDefaultOptions(logLevel: LogLevel?) {
        if (nativeHandle == 0L) return
        val mpvLevel =
            when (logLevel) {
                null -> "no"
                LogLevel.ERROR -> "error"
                LogLevel.WARN -> "warn"
                LogLevel.INFO -> "info"
                LogLevel.DEBUG -> "v"
            }
        if (!nativeSetLogLevel(nativeHandle, mpvLevel)) {
            Log.w(TAG, "mpv setLogLevel($mpvLevel) failed: ${lastError().orEmpty()}")
        }
        if (logLevel == LogLevel.DEBUG) {
            // Increase verbosity for networking/demuxing issues (HTTP status, redirects, range, etc.).
            // Unknown modules are ignored by mpv, so this is safe across versions.
            setOption("msg-level", "all=v,ffmpeg=trace,stream=trace,network=trace")
            // Ask libavformat/libavcodec to output trace logs (useful for HTTP and demuxer errors).
            setOption("demuxer-lavf-o", "loglevel=trace")
        }
    }

    fun setUserAgent(userAgent: String) {
        if (nativeHandle == 0L) return
        if (userAgent.isBlank()) return
        setOption("user-agent", userAgent)
    }

    fun setForceSeekable(enabled: Boolean) {
        if (nativeHandle == 0L) return
        setOption("force-seekable", if (enabled) "yes" else "no")
    }

    fun setHwdecPriority(value: String) {
        if (nativeHandle == 0L) return
        if (value.isBlank()) return
        setOption("hwdec", value)
    }

    fun setAudioOutput(value: String) {
        if (nativeHandle == 0L) return
        if (value.isBlank()) return
        setOption("ao", value)
    }

    fun setVideoSync(value: String) {
        if (nativeHandle == 0L) return
        if (value.isBlank()) return
        setOption("video-sync", value)
    }

    fun setVideoOutput(output: String) {
        if (nativeHandle == 0L) return
        if (output.isBlank()) return
        setOption("vo", output)
    }

    fun setSubtitleFonts(
        fontsDir: String,
        defaultFontFamily: String
    ) {
        if (nativeHandle == 0L) return
        if (fontsDir.isNotBlank()) {
            setOption("sub-fonts-dir", fontsDir)
        }
        if (defaultFontFamily.isNotBlank()) {
            setOption("sub-font", defaultFontFamily)
        }
        // On Android builds we ship fonts via app cache, so don't rely on system font providers.
        setOption("sub-font-provider", "none")
    }

    fun setSurfaceSize(
        width: Int,
        height: Int
    ) {
        if (nativeHandle == 0L) return
        if (width <= 0 || height <= 0) return
        setOption("android-surface-size", "${width}x$height")
    }

    fun setEventListener(listener: (Event) -> Unit) {
        synchronized(listenerLock) {
            eventListeners.clear()
            eventListeners.add(listener)
        }
        if (nativeHandle != 0L) {
            startEventLoop()
        }
    }

    fun addEventListener(listener: (Event) -> Unit) {
        synchronized(listenerLock) {
            eventListeners.add(listener)
        }
        if (nativeHandle != 0L) {
            startEventLoop()
        }
    }

    fun removeEventListener(listener: (Event) -> Unit) {
        val shouldStop =
            synchronized(listenerLock) {
                eventListeners.remove(listener)
                eventListeners.isEmpty()
            }
        if (shouldStop && nativeHandle != 0L && eventLoopStarted) {
            nativeStopEventLoop(nativeHandle)
            eventLoopStarted = false
        }
    }

    fun clearEventListener() {
        synchronized(listenerLock) {
            eventListeners.clear()
        }
        if (nativeHandle != 0L && eventLoopStarted) {
            nativeStopEventLoop(nativeHandle)
        }
        eventLoopStarted = false
    }

    fun videoSize(): Pair<Int, Int> {
        if (nativeHandle == 0L) return 0 to 0
        val packed = nativeGetVideoSize(nativeHandle)
        val width = (packed shr 32).toInt()
        val height = (packed and 0xffffffffL).toInt()
        return width to height
    }

    fun hwdecCurrent(): String? {
        if (nativeHandle == 0L) return null
        return nativeGetHwdecCurrent(nativeHandle)
    }

    fun listTracks(): List<TrackInfo> {
        if (nativeHandle == 0L) return emptyList()
        val rawTracks = nativeListTracks(nativeHandle) ?: return emptyList()
        return rawTracks.mapNotNull { raw ->
            val parts = raw.split("|")
            if (parts.size < 4) return@mapNotNull null
            val nativeType = parts[0].toIntOrNull() ?: return@mapNotNull null
            val id = parts[1].toIntOrNull() ?: return@mapNotNull null
            val selected = parts[2] == "1"
            val title = parts[3].ifEmpty { "Track $id" }
            val trackType =
                when (nativeType) {
                    TRACK_TYPE_AUDIO -> TrackType.AUDIO
                    TRACK_TYPE_SUBTITLE -> TrackType.SUBTITLE
                    else -> return@mapNotNull null
                }
            TrackInfo(id, trackType, nativeType, title, selected)
        }
    }

    fun selectTrack(
        nativeType: Int,
        trackId: Int
    ): Boolean {
        if (nativeHandle == 0L) return false
        return nativeSelectTrack(nativeHandle, nativeType, trackId)
    }

    fun deselectTrack(nativeType: Int): Boolean {
        if (nativeHandle == 0L) return false
        return nativeDeselectTrack(nativeHandle, nativeType)
    }

    fun addExternalTrack(
        type: TrackType,
        path: String
    ): Boolean {
        if (nativeHandle == 0L || path.isEmpty()) return false
        val nativeType =
            when (type) {
                TrackType.AUDIO -> TRACK_TYPE_AUDIO
                TrackType.SUBTITLE -> TRACK_TYPE_SUBTITLE
                else -> return false
            }
        return nativeAddExternalTrack(nativeHandle, nativeType, path)
    }

    fun addShader(path: String): Boolean {
        if (nativeHandle == 0L || path.isBlank()) return false
        return nativeAddShader(nativeHandle, path)
    }

    fun setShaders(value: String): Boolean {
        if (nativeHandle == 0L) return false
        return nativeSetShaders(nativeHandle, value)
    }

    fun clearShaders(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeClearShaders(nativeHandle)
    }

    fun currentPosition(): Long {
        if (nativeHandle == 0L) return 0
        return nativeGetPosition(nativeHandle)
    }

    fun duration(): Long {
        if (nativeHandle == 0L) return 0
        return nativeGetDuration(nativeHandle)
    }

    @Keep
    private fun onNativeEvent(
        type: Int,
        arg1: Long,
        arg2: Long,
        message: String?
    ) {
        val event =
            when (type) {
                EVENT_PREPARED -> Event.Prepared
                EVENT_VIDEO_SIZE -> Event.VideoSize(arg1.toInt(), arg2.toInt())
                EVENT_RENDERING_START -> Event.RenderingStart
                EVENT_COMPLETED -> Event.Completed
                EVENT_BUFFERING_START -> Event.Buffering(true)
                EVENT_BUFFERING_END -> Event.Buffering(false)
                EVENT_ERROR -> Event.Error(arg1.toInt(), arg2.toInt(), message)
                EVENT_LOG_MESSAGE -> Event.LogMessage(arg1.toInt(), message)
                else -> null
            }

        if (event == null) {
            return
        }

        val listeners =
            synchronized(listenerLock) {
                eventListeners.toList()
            }
        if (listeners.isEmpty()) {
            return
        }

        mainHandler.post {
            listeners.forEach { listener ->
                runCatching { listener(event) }
            }
        }
    }

    private fun startEventLoop() {
        if (eventLoopStarted || nativeHandle == 0L) return
        nativeStartEventLoop(nativeHandle, this)
        eventLoopStarted = true
    }

    companion object {
        private const val EVENT_PREPARED = 1
        private const val EVENT_VIDEO_SIZE = 2
        private const val EVENT_RENDERING_START = 3
        private const val EVENT_COMPLETED = 4
        private const val EVENT_ERROR = 5
        private const val EVENT_BUFFERING_START = 6
        private const val EVENT_BUFFERING_END = 7
        private const val EVENT_LOG_MESSAGE = 8

        const val TRACK_TYPE_AUDIO = 1
        const val TRACK_TYPE_SUBTITLE = 2

        private val nativeLoaded: Boolean
        private val nativeLinked: Boolean
        private val availabilityMessage: String?

        @Volatile
        private var appContextRegistered: Boolean = false

        init {
            var loaded = false
            var linked = false
            var availability: String? = null
            try {
                System.loadLibrary("mpv_bridge")
                loaded = true
                linked = nativeIsLinked()
                if (!linked) {
                    availability = nativeLastError()
                        ?: "libmpv.so not packaged; mpv bridge will operate in stub mode"
                    Log.w(TAG, availability)
                }
            } catch (e: UnsatisfiedLinkError) {
                availability = "Failed to load mpv_bridge: ${e.message}"
                Log.e(TAG, availability)
            } catch (e: SecurityException) {
                availability = "SecurityException loading mpv_bridge: ${e.message}"
                Log.e(TAG, availability)
            }
            nativeLoaded = loaded
            nativeLinked = linked
            availabilityMessage = availability
        }

        fun registerAndroidAppContext(context: Context) {
            if (!nativeLoaded || !nativeLinked || appContextRegistered) return
            try {
                nativeSetAndroidAppContext(context.applicationContext)
                appContextRegistered = true
            } catch (e: Throwable) {
                Log.w(TAG, "nativeSetAndroidAppContext failed: ${e.message}")
            }
        }

        @JvmStatic
        private external fun nativeLastError(): String?

        @JvmStatic
        private external fun nativeIsLinked(): Boolean

        @JvmStatic
        private external fun nativeCreate(): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeSetSurface(
            handle: Long,
            surface: Surface?
        )

        @JvmStatic
        private external fun nativeStartEventLoop(
            handle: Long,
            bridge: MpvNativeBridge
        )

        @JvmStatic
        private external fun nativeStopEventLoop(handle: Long)

        @JvmStatic
        private external fun nativeSetOptionString(
            handle: Long,
            name: String,
            value: String
        ): Boolean

        @JvmStatic
        private external fun nativeSetLogLevel(
            handle: Long,
            level: String
        ): Boolean

        @JvmStatic
        private external fun nativeGetVideoSize(handle: Long): Long

        @JvmStatic
        private external fun nativeGetHwdecCurrent(handle: Long): String?

        @JvmStatic
        private external fun nativeListTracks(handle: Long): Array<String>?

        @JvmStatic
        private external fun nativeSelectTrack(
            handle: Long,
            trackType: Int,
            trackId: Int
        ): Boolean

        @JvmStatic
        private external fun nativeDeselectTrack(
            handle: Long,
            trackType: Int
        ): Boolean

        @JvmStatic
        private external fun nativeAddExternalTrack(
            handle: Long,
            trackType: Int,
            path: String
        ): Boolean

        @JvmStatic
        private external fun nativeAddShader(
            handle: Long,
            path: String
        ): Boolean

        @JvmStatic
        private external fun nativeSetShaders(
            handle: Long,
            value: String
        ): Boolean

        @JvmStatic
        private external fun nativeClearShaders(handle: Long): Boolean

        @JvmStatic
        private external fun nativeSetDataSource(
            handle: Long,
            path: String,
            headers: Array<String>?
        ): Boolean

        @JvmStatic
        private external fun nativePlay(handle: Long)

        @JvmStatic
        private external fun nativePause(handle: Long)

        @JvmStatic
        private external fun nativeStop(handle: Long)

        @JvmStatic
        private external fun nativeSeek(
            handle: Long,
            positionMs: Long
        )

        @JvmStatic
        private external fun nativeSetSpeed(
            handle: Long,
            speed: Float
        )

        @JvmStatic
        private external fun nativeSetVolume(
            handle: Long,
            volume: Float
        )

        @JvmStatic
        private external fun nativeSetLooping(
            handle: Long,
            looping: Boolean
        )

        @JvmStatic
        private external fun nativeSetSubtitleDelay(
            handle: Long,
            offsetMs: Long
        )

        @JvmStatic
        private external fun nativeGetPosition(handle: Long): Long

        @JvmStatic
        private external fun nativeGetDuration(handle: Long): Long

        @JvmStatic
        private external fun nativeSetAndroidAppContext(context: Any)
    }

    private fun setOption(
        name: String,
        value: String
    ) {
        val success = nativeSetOptionString(nativeHandle, name, value)
        if (!success) {
            Log.w(TAG, "mpv option $name=$value rejected: ${lastError().orEmpty()}")
        }
    }
}
