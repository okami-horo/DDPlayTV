package com.xyoye.player.kernel.impl.vlc

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Point
import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Surface
import com.xyoye.common_component.storage.file.helper.HttpPlayServer
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.IOUtils
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.TrackType
import com.xyoye.data_component.enums.VLCAudioOutput
import com.xyoye.data_component.enums.VLCHWDecode
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.kernel.subtitle.SubtitleKernelBridge
import com.xyoye.player.utils.PlayerConstant
import com.xyoye.player.utils.VideoLog
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IMedia.VideoTrack
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.Locale

/**
 * Created by xyoye on 2021/4/12.
 */

class VlcVideoPlayer(
    private val mContext: Context
) : AbstractVideoPlayer(),
    SubtitleKernelBridge {
    companion object {
        private val TAG = VlcVideoPlayer::class.java.simpleName

        @Volatile
        var playbackState = PlaybackStateCompat.STATE_NONE
            private set
    }

    private lateinit var libVlc: LibVLC
    private lateinit var mMediaPlayer: MediaPlayer
    private lateinit var mMedia: Media
    private var videoSourceFd: AssetFileDescriptor? = null
    private lateinit var audioOutput: VLCAudioOutput

    private var mCurrentDuration = 0L
    private var seekable = true
    private var isBufferEnd = false
    private var dataSource: String? = null
    private var proxySeekEnabled: Boolean = false
    private val mVideoSize = Point(0, 0)
    private var looping = PlayerInitializer.isLooping

    override fun initPlayer() {
        audioOutput = PlayerInitializer.Player.vlcAudioOutput
        setOptions()
        mMediaPlayer = MediaPlayer(libVlc)
        mMediaPlayer.setAudioOutput(audioOutput.value)
        initVLCEventListener()
    }

    override fun setDataSource(
        path: String,
        headers: Map<String, String>?
    ) {
        isBufferEnd = false
        dataSource = path
        runCatching {
            val playServer = HttpPlayServer.getInstance()
            if (playServer.isServingUrl(path)) {
                playServer.setSeekEnabled(false)
                proxySeekEnabled = false
            }
        }

        val vlcMedia = createVlcMedia(path, headers)
        if (vlcMedia == null) {
            mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_URL_EMPTY, 0)
            return
        }

        mMedia = vlcMedia
        val hwMode = PlayerInitializer.Player.vlcHWDecode
        val shouldForceNoDirectRendering =
            hwMode == VLCHWDecode.HW_ACCELERATION_AUTO && isLikelyHevc10BitSource(path)

        when (hwMode) {
            VLCHWDecode.HW_ACCELERATION_DISABLE -> mMedia.setHWDecoderEnabled(false, false)
            VLCHWDecode.HW_ACCELERATION_DECODING -> {
                mMedia.setHWDecoderEnabled(true, true)
                mMedia.disableDirectRendering()
            }
            VLCHWDecode.HW_ACCELERATION_FULL -> mMedia.setHWDecoderEnabled(true, true)
            VLCHWDecode.HW_ACCELERATION_AUTO -> {
                mMedia.setHWDecoderEnabled(true, true)
                if (shouldForceNoDirectRendering) {
                    mMedia.disableDirectRendering()
                    VideoLog.d("$TAG--setDataSource--> Force non-direct MediaCodec for suspected HEVC 10bit source")
                }
            }
        }

        mCurrentDuration = mMedia.duration
        mMediaPlayer.media = mMedia
        mMedia.release()
    }

    override fun setSurface(surface: Surface) {
    }

    override fun prepareAsync() {
        mMediaPlayer.play()
    }

    override fun start() {
        mMediaPlayer.play()
    }

    override fun pause() {
        mMediaPlayer.pause()
    }

    override fun stop() {
        playbackState = PlaybackStateCompat.STATE_STOPPED

        if (mMediaPlayer.hasMedia() && !mMediaPlayer.isReleased) {
            mMediaPlayer.stop()
        }
    }

    override fun reset() {
    }

    override fun release() {
        clearPlayerEventListener()
        stop()
        IOUtils.closeIO(videoSourceFd)
        mMediaPlayer.setEventListener(null)
        if (isVideoPlaying()) {
            mMediaPlayer.vlcVout.detachViews()
        }
        mMediaPlayer.media?.apply {
            setEventListener(null)
            release()
        }
        SupervisorScope.IO.launch {
            mMediaPlayer.release()
        }
    }

    override fun seekTo(timeMs: Long) {
        if (seekable && isPlayerAvailable()) {
            mMediaPlayer.time = timeMs
        }
    }

    override fun setSpeed(speed: Float) {
        mMediaPlayer.rate = speed
    }

    override fun setVolume(
        leftVolume: Float,
        rightVolume: Float
    ) {
        val volume = ((leftVolume + rightVolume) / 2 * 100).toInt()
        mMediaPlayer.volume = volume
    }

    override fun setLooping(isLooping: Boolean) {
        looping = isLooping
    }

    override fun setOptions() {
        val options = arrayListOf<String>()
        options.add("-vvv")
        options.add("--aout=${audioOutput.value}")
        libVlc = LibVLC(mContext, options)
    }

    override fun setSubtitleOffset(offsetMs: Long) {
        mMediaPlayer.spuDelay = offsetMs * 1000
    }

    override fun isPlaying(): Boolean = mMediaPlayer.isPlaying && isBufferEnd

    override fun getCurrentPosition(): Long = mMediaPlayer.time

    override fun getDuration(): Long = mCurrentDuration

    override fun getSpeed(): Float = mMediaPlayer.rate

    override fun getVideoSize(): Point = mVideoSize

    override fun getBufferedPercentage(): Int = 0

    override fun supportBufferedPercentage(): Boolean = false

    override fun getTcpSpeed(): Long = 0

    override fun getTracks(type: TrackType): List<VideoTrackBean> =
        getVlcTrackType(type)
            ?.let {
                mMediaPlayer.getTracks(it)
            }?.map {
                VideoTrackBean.internal(it.id, it.name, type, selected = it.selected)
            } ?: emptyList()

    override fun selectTrack(track: VideoTrackBean) {
        if (isPlayerAvailable() && track.id.isNullOrEmpty().not()) {
            mMediaPlayer.selectTrack(track.id)
            seekTo(mMediaPlayer.time)
        }
    }

    override fun deselectTrack(type: TrackType) {
        getVlcTrackType(type)?.let {
            mMediaPlayer.unselectTrackType(it)
            seekTo(mMediaPlayer.time)
        }
    }

    override fun supportAddTrack(type: TrackType): Boolean = type == TrackType.SUBTITLE || type == TrackType.AUDIO

    override fun addTrack(track: VideoTrackBean): Boolean {
        return when (track.type) {
            TrackType.AUDIO -> {
                val audioPath = track.type.getAudio(track.trackResource) ?: return false
                mMediaPlayer.addSlave(IMedia.Slave.Type.Audio, audioPath, true)
            }

            TrackType.SUBTITLE -> {
                val subtitlePath = track.type.getSubtitle(track.trackResource) ?: return false
                mMediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, subtitlePath, true)
            }

            else -> return false
        }
    }

    private fun getVlcTrackType(type: TrackType): Int? =
        when (type) {
            TrackType.AUDIO -> IMedia.Track.Type.Audio
            TrackType.SUBTITLE -> IMedia.Track.Type.Text
            else -> null
        }

    fun attachRenderView(vlcVideoLayout: VLCVideoLayout) {
        if (mMediaPlayer.vlcVout.areViewsAttached()) {
            mMediaPlayer.detachViews()
        }
        val isTextureView = false
        mMediaPlayer.attachViews(vlcVideoLayout, null, true, isTextureView)
    }

    fun setScale(scale: MediaPlayer.ScaleType) {
        mMediaPlayer.videoScale = scale
    }

    private fun initVLCEventListener() {
        mMediaPlayer.setEventListener {
            // VlcEventLog.log(it)
            when (it.type) {
                // 缓冲
                MediaPlayer.Event.Buffering -> {
                    isBufferEnd = it.buffering == 100f
                    if (it.buffering == 100f) {
                        mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_BUFFERING_END, 0)
                        VideoLog.d("$TAG--listener--onInfo--> MEDIA_INFO_BUFFERING_END")
                    } else {
                        mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_BUFFERING_START, 0)
                        VideoLog.d("$TAG--listener--onInfo--> MEDIA_INFO_BUFFERING_START")
                    }
                }
                // 打开中
                MediaPlayer.Event.Opening -> {
                }
                // 播放中
                MediaPlayer.Event.Playing -> playbackState = PlaybackStateCompat.STATE_PLAYING
                // 已暂停
                MediaPlayer.Event.Paused -> playbackState = PlaybackStateCompat.STATE_PAUSED
                // 是否可跳转
                MediaPlayer.Event.SeekableChanged -> seekable = it.seekable
                // 播放错误
                MediaPlayer.Event.EncounteredError -> {
                    VlcAudioPolicy.markCompatAfterError()
                    mPlayerEventListener.onError()
                    VideoLog.d("$TAG--listener--onInfo--> onError")
                }
                // 时长输出
                MediaPlayer.Event.LengthChanged -> {
                    mCurrentDuration = it.lengthChanged
                }

                MediaPlayer.Event.ESSelected -> {
                    if (it.esChangedType == IMedia.Track.Type.Video) {
                        val track = mMediaPlayer.getSelectedTrack(IMedia.Track.Type.Video)
                        (track as? VideoTrack)?.let { videoTrack ->
                            mVideoSize.x = videoTrack.width
                            mVideoSize.y = videoTrack.height
                        }
                    }
                }
                // 视频输出
                MediaPlayer.Event.Vout -> {
                    if (it.voutCount > 0) {
                        mMediaPlayer.updateVideoSurfaces()
                        val path = dataSource
                        if (!proxySeekEnabled && !path.isNullOrEmpty()) {
                            runCatching {
                                val playServer = HttpPlayServer.getInstance()
                                if (playServer.isServingUrl(path)) {
                                    playServer.setSeekEnabled(true)
                                    proxySeekEnabled = true
                                }
                            }
                        }

                        mPlayerEventListener.onInfo(
                            PlayerConstant.MEDIA_INFO_VIDEO_RENDERING_START,
                            0,
                        )
                        VideoLog.d("$TAG--listener--onInfo--> MEDIA_INFO_VIDEO_RENDERING_START")
                    }
                }
                // 播放完成
                MediaPlayer.Event.EndReached -> {
                    if (looping) {
                        if (isPlayerAvailable()) {
                            mMediaPlayer.time = 0L
                            mMediaPlayer.play()
                        }
                        VideoLog.d("$TAG--listener--onInfo--> loop restart")
                    } else {
                        mPlayerEventListener.onCompletion()
                        VideoLog.d("$TAG--listener--onInfo--> onCompletion")
                    }
                }
            }
        }
    }

    private fun createVlcMedia(
        path: String,
        headers: Map<String, String>?
    ): Media? {
        if (path.isEmpty()) {
            return null
        }

        val videoUri =
            if (path.startsWith("/")) {
                Uri.fromFile(File(path))
            } else {
                Uri.parse(path)
            }

        // content://
        return if (videoUri.scheme == ContentResolver.SCHEME_CONTENT) {
            IOUtils.closeIO(videoSourceFd)
            videoSourceFd =
                try {
                    mContext.contentResolver.openAssetFileDescriptor(videoUri, "r")
                } catch (e: Exception) {
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        e,
                        "VlcVideoPlayer",
                        "createVlcMedia",
                        "Failed to open asset file descriptor for URI: $videoUri",
                    )
                    e.printStackTrace()
                    null
                }

            videoSourceFd?.run { Media(libVlc, this) }
        } else {
            Media(libVlc, videoUri).apply {
                applyHttpHeaders(headers)
            }
        }
    }

    private fun Media.applyHttpHeaders(headers: Map<String, String>?) {
        if (headers.isNullOrEmpty()) return
        headers.forEach { (key, value) ->
            when {
                key.equals("user-agent", true) -> addOption(":http-user-agent=$value")
                key.equals("referer", true) -> addOption(":http-referrer=$value")
                key.equals("cookie", true) -> addOption(":http-cookie=$value")
                key.equals("authorization", true) -> addOption(":http-authorization=$value")
            }
        }
    }

    private fun Media.disableDirectRendering() {
        addOption(":no-mediacodec-dr")
        addOption(":no-omxil-dr")
    }

    private fun isLikelyHevc10BitSource(path: String): Boolean {
        val decodedPath = Uri.decode(path).lowercase(Locale.getDefault())
        val hevcKeywords = listOf("hevc", "h265", "x265")
        val tenBitKeywords = listOf("10bit", "p10", "yuv420p10", "main10")
        val containsHevc = hevcKeywords.any { decodedPath.contains(it) }
        val containsTenBit = tenBitKeywords.any { decodedPath.contains(it) }
        return containsHevc && containsTenBit
    }

    private fun isPlayerAvailable() = mMediaPlayer.hasMedia() && !mMediaPlayer.isReleased

    private fun isVideoPlaying() = !mMediaPlayer.isReleased && mMediaPlayer.vlcVout.areViewsAttached()
}
