package com.xyoye.player

import android.content.Context
import android.graphics.PointF
import android.media.AudioManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.xyoye.cache.CacheManager
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.PlayState
import com.xyoye.data_component.enums.TrackType
import com.xyoye.data_component.enums.VideoScreenScale
import com.xyoye.player.controller.VideoController
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.anime4k.Anime4kMode
import com.xyoye.player.kernel.facoty.PlayerFactory
import com.xyoye.player.kernel.impl.media3.Media3VideoPlayer
import com.xyoye.player.kernel.impl.mpv.MpvVideoPlayer
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.kernel.inter.VideoPlayerEventListener
import com.xyoye.player.kernel.subtitle.SubtitleKernelBridge
import com.xyoye.player.subtitle.backend.CanvasTextRendererBackend
import com.xyoye.player.subtitle.backend.LibassRendererBackend
import com.xyoye.player.subtitle.backend.SubtitleFallbackDispatcher
import com.xyoye.player.subtitle.backend.SubtitleRenderEnvironment
import com.xyoye.player.subtitle.backend.SubtitleRenderer
import com.xyoye.player.subtitle.backend.SubtitleRendererRegistry
import com.xyoye.player.surface.InterSurfaceView
import com.xyoye.player.surface.SurfaceFactory
import com.xyoye.player.utils.AudioFocusHelper
import com.xyoye.player.utils.DecodeType
import com.xyoye.player.utils.PlaybackErrorFormatter
import com.xyoye.player.utils.PlayerConstant
import com.xyoye.player.wrapper.InterVideoPlayer
import com.xyoye.player.wrapper.InterVideoTrack
import com.xyoye.player_component.utils.PlayRecorder
import com.xyoye.subtitle.MixedSubtitle

/**
 * Created by xyoye on 2020/11/3.
 */

@UnstableApi
class DanDanVideoPlayer(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs, 0),
    InterVideoPlayer,
    InterVideoTrack,
    VideoPlayerEventListener {
    // 播放状态
    private var mCurrentPlayState = PlayState.STATE_IDLE

    @Volatile
    private var lastPlaybackError: Exception? = null

    // 默认组件参数
    private val mDefaultLayoutParams =
        LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        )

    // 音频焦点监听
    private var mAudioFocusHelper: AudioFocusHelper

    // 视图控制器
    private var mVideoController: VideoController? = null

    // 渲染视图组件
    private var mRenderView: InterSurfaceView? = null

    // 播放器
    private lateinit var mVideoPlayer: AbstractVideoPlayer

    // 播放资源
    private lateinit var videoSource: BaseVideoSource

    // 当前音量
    private var mCurrentVolume = PointF(0f, 0f)

    // 当前视图缩放类型
    private var mScreenScale = PlayerInitializer.screenScale

    private var subtitleRenderer: SubtitleRenderer? = null
    private var subtitleFallbackDispatcher: SubtitleFallbackDispatcher =
        SubtitleFallbackDispatcher.NO_OP

    init {
        val audioManager =
            context.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val lifecycleScope = (context as AppCompatActivity).lifecycleScope
        mAudioFocusHelper = AudioFocusHelper(this, audioManager, lifecycleScope)
    }

    override fun start() {
        if (mVideoController == null) {
            throw RuntimeException("controller must initialized before start")
        }

        var isStartedPlay = false
        if (mCurrentPlayState == PlayState.STATE_IDLE || mCurrentPlayState == PlayState.STATE_START_ABORT) {
            initPlayer()
            ensureSubtitleRenderer()
            isStartedPlay = startPrepare()
        } else if (isInPlayState()) {
            mVideoPlayer.start()
            setPlayState(PlayState.STATE_PLAYING)
            isStartedPlay = true
        }

        if (isStartedPlay) {
            keepScreenOn = true
            mAudioFocusHelper.requestFocus()
        }
    }

    override fun pause() {
        if (isInPlayState() && mVideoPlayer.isPlaying()) {
            setPlayState(PlayState.STATE_PAUSED)
            mVideoPlayer.pause()
            mAudioFocusHelper.abandonFocus()
            keepScreenOn = false
        }
    }

    override fun getVideoSource(): BaseVideoSource = videoSource

    override fun getDuration(): Long {
        if (isInPlayState()) {
            return mVideoPlayer.getDuration()
        }
        return 0
    }

    override fun getCurrentPosition(): Long {
        if (isInPlayState()) {
            return mVideoPlayer.getCurrentPosition()
        }
        return 0
    }

    override fun seekTo(timeMs: Long) {
        if (timeMs >= 0 && isInPlayState()) {
            mVideoPlayer.seekTo(timeMs)
            subtitleRenderer?.onSeek(timeMs)
        }
    }

    override fun isPlaying() = isInPlayState() && mVideoPlayer.isPlaying()

    override fun getBufferedPercentage() = mVideoPlayer.getBufferedPercentage()

    override fun supportBufferedPercentage(): Boolean = this::mVideoPlayer.isInitialized && mVideoPlayer.supportBufferedPercentage()

    override fun setSilence(isSilence: Boolean) {
        val volume = if (isSilence) 0f else 1f
        setVolume(PointF(volume, volume))
    }

    override fun isSilence(): Boolean = mCurrentVolume.x + mCurrentVolume.y == 0f

    override fun setVolume(point: PointF) {
        mCurrentVolume = point
        mVideoPlayer.setVolume(mCurrentVolume.x, mCurrentVolume.y)
    }

    override fun getVolume() = mCurrentVolume

    override fun setScreenScale(scaleType: VideoScreenScale) {
        mScreenScale = scaleType
        mRenderView?.setScaleType(mScreenScale)
    }

    override fun setSpeed(speed: Float) {
        if (isInPlayState()) {
            mVideoPlayer.setSpeed(speed)
        }
    }

    override fun getSpeed(): Float {
        if (isInPlayState()) {
            return mVideoPlayer.getSpeed()
        }
        return 1f
    }

    override fun getTcpSpeed() = mVideoPlayer.getTcpSpeed()

    override fun supportTcpSpeed(): Boolean = this::mVideoPlayer.isInitialized && mVideoPlayer.supportTcpSpeed()

    override fun getDecodeType(): DecodeType =
        if (this::mVideoPlayer.isInitialized) {
            mVideoPlayer.getDecodeType()
        } else {
            DecodeType.HW
        }

    override fun getAnime4kMode(): Int {
        if (!this::mVideoPlayer.isInitialized) {
            return Anime4kMode.MODE_OFF
        }
        return when (val videoPlayer = mVideoPlayer) {
            is MpvVideoPlayer -> videoPlayer.getAnime4kMode()
            is Media3VideoPlayer -> videoPlayer.getAnime4kMode()
            else -> Anime4kMode.MODE_OFF
        }
    }

    override fun setAnime4kMode(mode: Int) {
        if (!this::mVideoPlayer.isInitialized) {
            return
        }
        when (val videoPlayer = mVideoPlayer) {
            is MpvVideoPlayer -> videoPlayer.setAnime4kMode(mode)
            is Media3VideoPlayer -> videoPlayer.setAnime4kMode(mode)
        }
    }

    override fun isSeekable(): Boolean {
        val exoPlayer = exoPlayerOrNull()
        return exoPlayer?.isCurrentMediaItemSeekable ?: (getDuration() > 0)
    }

    override fun isLive(): Boolean {
        val exoPlayer = exoPlayerOrNull()
        return exoPlayer?.isCurrentMediaItemLive ?: false
    }

    override fun getRenderView(): InterSurfaceView? = mRenderView

    internal fun exoPlayerOrNull(): ExoPlayer? {
        if (!this::mVideoPlayer.isInitialized) {
            return null
        }
        return (mVideoPlayer as? Media3VideoPlayer)?.exoPlayerOrNull()
    }

    override fun getVideoSize() = mVideoPlayer.getVideoSize()

    override fun onVideoSizeChange(
        width: Int,
        height: Int
    ) {
        mRenderView?.setScaleType(mScreenScale)
        mRenderView?.setVideoSize(width, height)
        mVideoController?.setVideoSize(mVideoPlayer.getVideoSize())
    }

    override fun onPrepared() {
        setPlayState(PlayState.STATE_PREPARED)
    }

    override fun onError(e: Exception?) {
        lastPlaybackError = e
        val title =
            if (this::videoSource.isInitialized) {
                videoSource.getVideoTitle()
            } else {
                "<unknown>"
            }
        val position = getCurrentPosition()
        LogFacade.e(
            LogModule.PLAYER,
            TAG_PLAYBACK,
            "play error title=$title position=$position ${PlaybackErrorFormatter.format(e)}",
            throwable = e,
        )
        setPlayState(PlayState.STATE_ERROR)
        keepScreenOn = false
    }

    override fun onCompletion() {
        if (mCurrentPlayState != PlayState.STATE_ERROR) {
            setPlayState(PlayState.STATE_COMPLETED)
        }
        keepScreenOn = false
        PlayRecorder.recordProgress(videoSource, 0, getDuration())
    }

    override fun onInfo(
        what: Int,
        extra: Int
    ) {
        when (what) {
            PlayerConstant.MEDIA_INFO_BUFFERING_START -> {
                setPlayState(PlayState.STATE_BUFFERING_PAUSED)
            }

            PlayerConstant.MEDIA_INFO_BUFFERING_END -> {
                setPlayState(PlayState.STATE_BUFFERING_PLAYING)
            }

            PlayerConstant.MEDIA_INFO_VIDEO_RENDERING_START -> {
                setPlayState(PlayState.STATE_PLAYING)
                if (windowVisibility != View.VISIBLE) {
                    pause()
                }
            }

            PlayerConstant.MEDIA_INFO_VIDEO_ROTATION_CHANGED -> {
                mRenderView?.setVideoRotation(extra)
            }

            PlayerConstant.MEDIA_INFO_URL_EMPTY -> {
                setPlayState(PlayState.STATE_ERROR)
            }
        }
    }

    override fun onSubtitleTextOutput(subtitle: MixedSubtitle) {
        val handled = subtitleRenderer?.render(subtitle) ?: false
        if (!handled) {
            mVideoController?.onSubtitleTextOutput(subtitle)
        }
    }

    private fun initPlayer() {
        mAudioFocusHelper.enable = PlayerInitializer.isEnableAudioFocus
        // 初始化播放器
        mVideoPlayer =
            PlayerFactory
                .getFactory(PlayerInitializer.playerType)
                .createPlayer(context)
                .apply {
                    setPlayerEventListener(this@DanDanVideoPlayer)
                    initPlayer()
                }

        // 初始化渲染布局
        mRenderView?.apply {
            this@DanDanVideoPlayer.removeView(getView())
            release()
        }
        mRenderView =
            SurfaceFactory
                .getFactory(
                    PlayerInitializer.playerType,
                    PlayerInitializer.surfaceType,
                ).createRenderView(context)
                .apply {
                    this@DanDanVideoPlayer.addView(getView(), 0, mDefaultLayoutParams)
                    attachPlayer(mVideoPlayer)
                }

        setExtraOption()
    }

    private fun setExtraOption() {
        mVideoPlayer.setLooping(PlayerInitializer.isLooping)
    }

    private fun configureSubtitleRenderer() {
        if (this::mVideoPlayer.isInitialized.not()) {
            return
        }
        val controller = mVideoController ?: return
        val environment =
            SubtitleRenderEnvironment(
                context,
                controller.getSubtitleController(),
                this,
                subtitleFallbackDispatcher,
            )

        val kernelBridge = mVideoPlayer as? SubtitleKernelBridge
        val requestedBackend = PlayerInitializer.Subtitle.backend
        val resolvedBackend =
            when (requestedBackend) {
                SubtitleRendererBackend.LIBASS ->
                    if (kernelBridge?.canStartGpuSubtitlePipeline() == false) {
                        SubtitleRendererBackend.LEGACY_CANVAS
                    } else {
                        SubtitleRendererBackend.LIBASS
                    }
                SubtitleRendererBackend.LEGACY_CANVAS -> SubtitleRendererBackend.LEGACY_CANVAS
            }
        PlayerInitializer.Subtitle.backend = resolvedBackend

        val renderer: SubtitleRenderer =
            if (resolvedBackend == SubtitleRendererBackend.LIBASS) {
                LibassRendererBackend(kernelBridge)
            } else {
                CanvasTextRendererBackend()
            }
        renderer.bind(environment)
        renderer.onSurfaceTypeChanged(PlayerInitializer.surfaceType)
        subtitleRenderer = renderer
        SubtitleRendererRegistry.register(renderer)
    }

    private fun ensureSubtitleRenderer() {
        if (subtitleRenderer == null) {
            configureSubtitleRenderer()
        }
    }

    private fun startPrepare(): Boolean =
        if (videoSource.getVideoUrl().isNotEmpty()) {
            mVideoPlayer.setDataSource(videoSource.getVideoUrl(), videoSource.getHttpHeader())
            mVideoPlayer.prepareAsync()
            setPlayState(PlayState.STATE_PREPARING)
            true
        } else {
            setPlayState(PlayState.STATE_ERROR)
            false
        }

    private fun setPlayState(playState: PlayState) {
        mCurrentPlayState = playState
        mVideoController?.setPlayState(playState)
    }

    private fun isInPlayState(): Boolean =
        this::mVideoPlayer.isInitialized &&
            mCurrentPlayState != PlayState.STATE_ERROR &&
            mCurrentPlayState != PlayState.STATE_IDLE &&
            mCurrentPlayState != PlayState.STATE_PREPARING &&
            mCurrentPlayState != PlayState.STATE_START_ABORT &&
            mCurrentPlayState != PlayState.STATE_COMPLETED

    fun resume() {
        if (isInPlayState() && !mVideoPlayer.isPlaying()) {
            setPlayState(PlayState.STATE_PLAYING)
            mVideoPlayer.start()
            mAudioFocusHelper.requestFocus()
            keepScreenOn = true
        }
    }

    /**
     * 保存播放信息
     */
    fun recordPlayInfo() {
        if (this::videoSource.isInitialized.not()) {
            return
        }
        // 保存最后一帧
        PlayRecorder.recordImage(videoSource.getUniqueKey(), mRenderView)
        // 保存播放进度
        PlayRecorder.recordProgress(videoSource, getCurrentPosition(), getDuration())
    }

    fun release() {
        val hasActiveResources =
            this::mVideoPlayer.isInitialized ||
                mCurrentPlayState != PlayState.STATE_IDLE ||
                subtitleRenderer != null ||
                mRenderView != null
        if (!hasActiveResources) {
            return
        }

        destroySubtitleRenderer()
        CacheManager.release()
        mVideoController?.destroy()
        if (this::mVideoPlayer.isInitialized) {
            mVideoPlayer.clearPlayerEventListener()
            runCatching { mVideoPlayer.release() }
        }
        keepScreenOn = false
        mRenderView?.run {
            this@DanDanVideoPlayer.removeView(getView())
            release()
        }
        mRenderView = null
        mAudioFocusHelper.abandonFocus()
        setPlayState(PlayState.STATE_IDLE)
    }

    fun onBackPressed(): Boolean = mVideoController?.onBackPressed() ?: false

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean = mVideoController?.onKeyDown(keyCode, event) ?: false

    fun setVideoSource(source: BaseVideoSource) {
        lastPlaybackError = null
        videoSource = source
    }

    fun getPlayState(): PlayState = mCurrentPlayState

    fun getLastPlaybackErrorOrNull(): Exception? = lastPlaybackError

    fun setController(controller: VideoController?) {
        destroySubtitleRenderer()
        removeView(mVideoController)
        mVideoController = controller
        mVideoController?.let {
            it.setMediaPlayer(this)
            addView(it, mDefaultLayoutParams)
        }
        if (this::mVideoPlayer.isInitialized) {
            configureSubtitleRenderer()
        }
    }

    fun enterPopupMode() {
        mVideoController?.setPopupMode(true)
        mRenderView?.refresh()
    }

    fun exitPopupMode() {
        mVideoController?.setPopupMode(false)
        mRenderView?.refresh()
    }

    fun attachSubtitleOverlay(view: View) {
        val controllerIndex = if (mVideoController != null) indexOfChild(mVideoController) else -1
        val insertIndex = if (controllerIndex >= 0) controllerIndex else childCount
        // IMPORTANT: do not reuse the same LayoutParams instance across children.
        // Reusing mDefaultLayoutParams caused overlay alignment code to mutate
        // gravity/margins on the render view, leading to asymmetric black bars.
        val lp =
            FrameLayout.LayoutParams(
                mDefaultLayoutParams.width,
                mDefaultLayoutParams.height,
                mDefaultLayoutParams.gravity,
            )
        addView(view, insertIndex, lp)
    }

    fun detachSubtitleOverlay(view: View) {
        removeView(view)
    }

    fun setSubtitleFallbackDispatcher(dispatcher: SubtitleFallbackDispatcher) {
        subtitleFallbackDispatcher = dispatcher
    }

    fun setPopupGestureHandler(handler: OnTouchListener?) {
        mVideoController?.setPopupGestureHandler(handler)
    }

    override fun updateSubtitleOffsetTime() {
        SubtitlePreferenceUpdater.persistOffset(PlayerInitializer.Subtitle.offsetPosition)
        if (this::mVideoPlayer.isInitialized && mCurrentPlayState != PlayState.STATE_IDLE) {
            mVideoPlayer.setSubtitleOffset(PlayerInitializer.Subtitle.offsetPosition)
        }
        subtitleRenderer?.onOffsetChanged(getCurrentPosition())
    }

    override fun supportAddTrack(type: TrackType): Boolean {
        if (this::mVideoPlayer.isInitialized.not() || mCurrentPlayState == PlayState.STATE_IDLE) {
            return false
        }
        return mVideoPlayer.supportAddTrack(type)
    }

    override fun addTrack(track: VideoTrackBean): Boolean {
        if (this::mVideoPlayer.isInitialized.not() || mCurrentPlayState == PlayState.STATE_IDLE) {
            return false
        }
        return mVideoPlayer.addTrack(track)
    }

    override fun getTracks(type: TrackType): List<VideoTrackBean> {
        if (this::mVideoPlayer.isInitialized.not() || mCurrentPlayState == PlayState.STATE_IDLE) {
            return emptyList()
        }
        return mVideoPlayer.getTracks(type)
    }

    override fun selectTrack(track: VideoTrackBean) {
        if (this::mVideoPlayer.isInitialized.not() || mCurrentPlayState == PlayState.STATE_IDLE) {
            return
        }
        mVideoPlayer.selectTrack(track)
    }

    override fun deselectTrack(type: TrackType) {
        if (this::mVideoPlayer.isInitialized.not() || mCurrentPlayState == PlayState.STATE_IDLE) {
            return
        }
        mVideoPlayer.deselectTrack(type)
    }

    fun switchSubtitleBackend(target: SubtitleRendererBackend) {
        if (PlayerInitializer.Subtitle.backend == target &&
            subtitleRenderer?.backend == target
        ) {
            return
        }

        PlayerInitializer.Subtitle.backend = target
        destroySubtitleRenderer()
        configureSubtitleRenderer()
    }

    private fun destroySubtitleRenderer() {
        val renderer = subtitleRenderer ?: return
        try {
            renderer.release()
        } finally {
            SubtitleRendererRegistry.unregister(renderer)
            subtitleRenderer = null
        }
    }

    private companion object {
        private const val TAG_PLAYBACK = "PlayerPlayback"
    }
}
