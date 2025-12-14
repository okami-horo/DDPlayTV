package com.xyoye.player.kernel.impl.mpv

import android.content.Context
import android.graphics.Point
import android.view.Surface
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.utils.PlayerConstant

class MpvVideoPlayer(
    private val context: Context
) : AbstractVideoPlayer() {

    private val nativeBridge = MpvNativeBridge()
    private var dataSource: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var videoSize = Point(0, 0)
    private var isPrepared = false
    private var isPreparing = false
    private var isPlaying = false
    private var playbackSpeed = PlayerInitializer.Player.videoSpeed
    private var looping = PlayerInitializer.isLooping
    private var initializationError: Exception? = null

    private fun failInitialization(message: String, code: Int? = null, cause: Throwable? = null): Exception {
        val error = MpvPlaybackException(message, code = code, cause = cause)
        initializationError = error
        mPlayerEventListener.onError(error)
        return error
    }

    override fun initPlayer() {
        initializationError = null
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
        nativeBridge.applyDefaultOptions(PlayerInitializer.isPrintLog)
        nativeBridge.setLooping(looping)
        nativeBridge.setSpeed(playbackSpeed)
    }

    override fun setDataSource(path: String, headers: Map<String, String>?) {
        dataSource = path
        this.headers = headers ?: emptyMap()
    }

    override fun setSurface(surface: Surface) {
        if (!nativeBridge.isAvailable) return
        nativeBridge.setSurface(surface)
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
        isPreparing = true
        var dataSourceError: Exception? = null
        val success = try {
            nativeBridge.setDataSource(path, headers)
        } catch (e: Exception) {
            dataSourceError = e
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "MpvVideoPlayer",
                "prepareAsync",
                "Failed to set mpv data source"
            )
            false
        }
        if (!success) {
            isPreparing = false
            val reason = nativeBridge.lastError()
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
        videoSize = Point(0, 0)
        initializationError = null
    }

    override fun release() {
        stop()
        nativeBridge.clearEventListener()
        nativeBridge.destroy()
        isPrepared = false
        isPreparing = false
        isPlaying = false
    }

    override fun seekTo(timeMs: Long) {
        if (!isPrepared) return
        nativeBridge.seek(timeMs)
    }

    override fun setSpeed(speed: Float) {
        playbackSpeed = speed
        if (!isPrepared) return
        nativeBridge.setSpeed(speed)
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
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

    override fun isPlaying(): Boolean {
        return isPrepared && isPlaying
    }

    override fun getCurrentPosition(): Long {
        if (!isPrepared) return 0
        return nativeBridge.currentPosition()
    }

    override fun getDuration(): Long {
        if (!isPrepared) return 0
        return nativeBridge.duration()
    }

    override fun getSpeed(): Float {
        return playbackSpeed
    }

    override fun getVideoSize(): Point {
        if (videoSize.x == 0 || videoSize.y == 0) {
            val (width, height) = nativeBridge.videoSize()
            videoSize = Point(width, height)
        }
        return videoSize
    }

    override fun getBufferedPercentage(): Int {
        return 0
    }

    override fun getTcpSpeed(): Long {
        return 0
    }

    override fun supportAddTrack(type: TrackType): Boolean {
        return type == TrackType.AUDIO || type == TrackType.SUBTITLE
    }

    override fun addTrack(track: VideoTrackBean): Boolean {
        val path = track.trackResource as? String ?: return false
        return nativeBridge.addExternalTrack(track.type, path)
    }

    override fun getTracks(type: TrackType): List<VideoTrackBean> {
        if (!isPrepared) return emptyList()
        val tracks = nativeBridge.listTracks()
            .filter { it.type == type }
            .map {
                VideoTrackBean.internal(
                    id = "${it.nativeType}:${it.id}",
                    name = it.title,
                    type = type,
                    selected = it.selected
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
        val nativeType = when (type) {
            TrackType.SUBTITLE -> MpvNativeBridge.TRACK_TYPE_SUBTITLE
            TrackType.AUDIO -> MpvNativeBridge.TRACK_TYPE_AUDIO
            else -> return
        }
        nativeBridge.deselectTrack(nativeType)
    }

    private fun onNativeEvent(event: MpvNativeBridge.Event) {
        when (event) {
            is MpvNativeBridge.Event.Buffering -> {
                mPlayerEventListener.onInfo(
                    if (event.started) PlayerConstant.MEDIA_INFO_BUFFERING_START else PlayerConstant.MEDIA_INFO_BUFFERING_END,
                    0
                )
            }
            is MpvNativeBridge.Event.Completed -> {
                isPlaying = false
                isPrepared = false
                isPreparing = false
                mPlayerEventListener.onCompletion()
            }
            is MpvNativeBridge.Event.Error -> {
                val readable = formatNativeError(
                    event.message ?: nativeBridge.lastError(),
                    event.code,
                    event.reason
                )
                val error = MpvPlaybackException(readable, code = event.code, reason = event.reason)
                initializationError = error
                isPrepared = false
                isPreparing = false
                isPlaying = false
                ErrorReportHelper.postCatchedExceptionWithContext(
                    error,
                    "MpvVideoPlayer",
                    "onNativeError",
                    readable
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
                mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_VIDEO_RENDERING_START, 0)
            }
            is MpvNativeBridge.Event.VideoSize -> {
                videoSize = Point(event.width, event.height)
                mPlayerEventListener.onVideoSizeChange(event.width, event.height)
            }
        }
    }

    private fun formatNativeError(message: String?, code: Int?, reason: Int?): String {
        val reasonLabel = when (reason) {
            0 -> "eof"
            1 -> "stop"
            2 -> "quit"
            3 -> "error"
            4 -> "redirect"
            else -> null
        }
        val details = buildList {
            if (!message.isNullOrBlank()) add(message)
            if (code != null) add("code=$code")
            if (reasonLabel != null) add("reason=$reasonLabel")
            else if (reason != null) add("reason=$reason")
        }
        return details.joinToString(" | ").ifEmpty { "mpv playback error" }
    }
}

private class MpvPlaybackException(
    message: String,
    val code: Int? = null,
    val reason: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)
