package com.xyoye.player_component.ui.activities.player

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.bridge.PlayTaskBridge
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.config.PlayerActions
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.extension.isTelevisionUiMode
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.media3.Media3SessionClient
import com.xyoye.common_component.playback.handoff.PlaybackAction
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.playback.userMessage
import com.xyoye.common_component.receiver.HeadsetBroadcastReceiver
import com.xyoye.common_component.receiver.PlayerReceiverListener
import com.xyoye.common_component.receiver.ScreenBroadcastReceiver
import com.xyoye.common_component.services.Media3SessionServiceProvider
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.utils.StatusBarStyle
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.DanmakuLanguage
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayState
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.SubtitleFallbackReason
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.data_component.enums.VLCAudioOutput
import com.xyoye.data_component.enums.VLCHWDecode
import com.xyoye.data_component.media3.entity.Media3BackgroundMode
import com.xyoye.data_component.media3.entity.Media3Capability
import com.xyoye.data_component.media3.entity.Media3PlaybackState
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player.controller.VideoController
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.impl.media3.Media3Diagnostics
import com.xyoye.player.kernel.impl.vlc.VlcAudioPolicy
import com.xyoye.player.subtitle.backend.SubtitleFallbackDispatcher
import com.xyoye.player.utils.PlaybackErrorFormatter
import com.xyoye.player_component.BR
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ActivityPlayerBinding
import com.xyoye.player_component.playback.session.BackendPlaybackState
import com.xyoye.player_component.playback.session.PlaybackActivityArguments
import com.xyoye.player_component.playback.session.PlaybackPositionConstraints
import com.xyoye.player_component.playback.session.PlaybackProgress
import com.xyoye.player_component.playback.session.PlaybackSessionBackend
import com.xyoye.player_component.playback.session.PreparedPlayback
import com.xyoye.player_component.playback.session.QueuedPlaybackStatus
import com.xyoye.player_component.playback.session.SessionRequestResult
import com.xyoye.player_component.sequential.Media3SequentialPlaybackAdapter
import com.xyoye.player_component.sequential.SequentialPlaybackBackendAdapter
import com.xyoye.player_component.sequential.SequentialPlaybackCoordinator
import com.xyoye.player_component.sequential.SequentialPlaybackQueueItemId
import com.xyoye.player_component.sequential.SequentialPlaybackSessionToken
import com.xyoye.player_component.sequential.SequentialQueueRetirement
import com.xyoye.player_component.utils.PlayRecorder
import com.xyoye.player_component.widgets.popup.PlayerPopupManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi
@Route(path = RouteTable.Player.Player)
class PlayerActivity :
    BaseActivity<PlayerViewModel, ActivityPlayerBinding>(),
    PlayerReceiverListener,
    SubtitleFallbackDispatcher {
    companion object {
        private const val TAG_ACTIVITY = "PlayerActivity"
        private const val TAG_SOURCE = "PlayerSource"
        private const val TAG_PLAYBACK = "PlayerPlayback"
        private const val TAG_DANMAKU = "PlayerDanmaku"
        private const val TAG_SUBTITLE = "PlayerSubtitle"
        private const val TAG_CONFIG = "PlayerConfig"
        private const val TAG_MEDIA3 = "Media3Session"
        private const val BACK_EXIT_CONFIRM_WINDOW_MS = 1500L
    }

    private val danmuViewModel: PlayerDanmuViewModel by lazy {
        ViewModelProvider(
            viewModelStore,
            ViewModelProvider.AndroidViewModelFactory(application)
        )[PlayerDanmuViewModel::class.java]
    }

    private val videoController: VideoController by lazy {
        VideoController(this)
    }

    private val danDanPlayer: DanDanVideoPlayer by lazy {
        DanDanVideoPlayer(this).apply {
            setSubtitleFallbackDispatcher(this@PlayerActivity)
        }
    }

    // 悬浮窗
    private val popupManager: PlayerPopupManager by lazy {
        PlayerPopupManager(this)
    }

    // 锁屏广播
    private lateinit var screenLockReceiver: ScreenBroadcastReceiver

    // 耳机广播
    private lateinit var headsetReceiver: HeadsetBroadcastReceiver

    // 外部请求退出播放器（例如媒体库断开/清理隐私时）
    private var exitPlayerReceiver: BroadcastReceiver? = null

    private var videoSource: BaseVideoSource? = null
    private var activityArguments = PlaybackActivityArguments(null, null)
    private var explicitPlaybackExit = false
    private var pauseAfterPrepare = false
    private var preparingProgress: PlaybackProgress? = null
    private var playingBeforeLifecyclePause = false
    private val preparationView by lazy {
        TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            isFocusable = false
            isClickable = false
            visibility = View.GONE
        }
    }

    /** Borrows the session's source. No source/Addon/lease is released by this adapter. */
    private val playbackBackend: PlaybackSessionBackend by lazy {
        object : PlaybackSessionBackend {
            override fun apply(
                playback: PreparedPlayback,
                version: PlaybackSessionVersion,
                progress: PlaybackProgress
            ) {
                danDanPlayer.recordPlayInfo()
                check(danDanPlayer.release()) { "BackendDetachFailed" }
                media3SequentialPlaybackAdapter.onBackendDetached()
                videoSource = null
                videoController.release()
                val source = playback.source
                check(source.getVideoUrl().isNotEmpty())
                initPlayerConfig(overrideProfile = playback.runtimeProfile)
                // Set the borrowed reference before starting: an apply failure may still retain it in the kernel.
                videoSource = source
                pauseAfterPrepare = !progress.playWhenReady
                val position = positionConstraints(source).normalize(progress.positionMs)
                preparingProgress = progress.copy(positionMs = position)
                updatePlayer(source, position)
                bindNativeQueue(playback, version)
                afterInitPlayer()
            }

            override fun currentSource() = (media3SequentialPlaybackAdapter.currentSource() ?: videoSource) as? StorageVideoSource

            override fun isAttached(source: StorageVideoSource) =
                videoSource === source || media3SequentialPlaybackAdapter.isAttached(source)

            override fun progress() =
                preparingProgress ?: PlaybackProgress(
                    danDanPlayer.getCurrentPosition(),
                    if (isChangingConfigurations) playingBeforeLifecyclePause else danDanPlayer.isPlaying()
                )

            override fun positionConstraints(source: StorageVideoSource) =
                PlaybackPositionConstraints(
                    seekable = currentSource() !== source || danDanPlayer.isSeekable(),
                    durationMs = if (currentSource() === source) danDanPlayer.getDuration().takeIf { it > 0 } else null
                )

            override fun localState(): BackendPlaybackState {
                val state =
                    when (danDanPlayer.getPlayState()) {
                        PlayState.STATE_PLAYING, PlayState.STATE_BUFFERING_PLAYING -> Media3PlaybackState.PLAYING
                        PlayState.STATE_PAUSED -> Media3PlaybackState.PAUSED
                        PlayState.STATE_COMPLETED -> Media3PlaybackState.COMPLETED
                        PlayState.STATE_ERROR, PlayState.STATE_START_ABORT -> Media3PlaybackState.FAILED
                        PlayState.STATE_BUFFERING_PAUSED -> Media3PlaybackState.BUFFERING
                        PlayState.STATE_PREPARED -> Media3PlaybackState.READY
                        else -> Media3PlaybackState.INITIALIZING
                    }
                // Popup is currently a no-op and the app service only exposes metadata.
                // Notification/PIP capabilities require working local operations first.
                val modes = emptySet<Media3BackgroundMode>()
                val capabilities =
                    buildSet {
                        add(Media3Capability.PLAY)
                        add(Media3Capability.PAUSE)
                        add(Media3Capability.SPEED)
                        add(Media3Capability.SUBTITLE)
                        if (danDanPlayer.isSeekable() && !danDanPlayer.isLive()) add(Media3Capability.SEEK)
                        if (danDanPlayer.supportAddTrack(TrackType.AUDIO)) add(Media3Capability.AUDIO_TRACK)
                    }
                return BackendPlaybackState(state, capabilities, modes)
            }

            override fun execute(action: PlaybackAction): Boolean {
                when (action) {
                    PlaybackAction.Play -> danDanPlayer.start()
                    PlaybackAction.Pause -> danDanPlayer.pause()
                    is PlaybackAction.Seek -> videoController.seekPlaybackTo(action.positionMs)
                    is PlaybackAction.SetSpeed -> videoController.changePlaybackSpeed(action.speed)
                    PlaybackAction.Exit -> finish()
                    PlaybackAction.EnterBackground -> enterTaskBackground()
                    PlaybackAction.EnterPictureInPicture -> {
                        enterPopupMode()
                        if (!popupManager.isShowing()) return false
                        enterTaskBackground()
                    }
                    is PlaybackAction.SelectQueueItem -> return false
                }
                return true
            }

            override fun enqueue(
                playback: PreparedPlayback,
                expected: PlaybackSessionVersion
            ): Boolean {
                val token = nativeQueueToken ?: return false
                if (token.version != expected || danDanPlayer.exoPlayerOrNull() == null) return false
                media3SequentialPlaybackAdapter.beginSession(token, checkNotNull(videoSource))
                return media3SequentialPlaybackAdapter
                    .queueNextSource(
                        token,
                        SequentialPlaybackQueueItemId(playback.candidateId),
                        playback.source
                    ).isSuccess
            }

            override fun reconcileCandidate(playback: PreparedPlayback): QueuedPlaybackStatus {
                val token = nativeQueueToken ?: return QueuedPlaybackStatus.DETACHED
                return when (media3SequentialPlaybackAdapter.invalidateSession(token)) {
                    SequentialQueueRetirement.CURRENT -> QueuedPlaybackStatus.CURRENT
                    SequentialQueueRetirement.DETACHED -> QueuedPlaybackStatus.DETACHED
                    SequentialQueueRetirement.STILL_ATTACHED -> QueuedPlaybackStatus.STILL_ATTACHED
                }
            }

            override fun adoptNative(
                playback: PreparedPlayback,
                version: PlaybackSessionVersion
            ) {
                val previous = videoSource
                if (previous != null) PlayRecorder.recordProgress(previous, 0, danDanPlayer.getDuration().coerceAtLeast(0))
                videoSource = playback.source
                danDanPlayer.setVideoSource(playback.source)
                videoController.setVideoTitle(playback.source.getVideoTitle())
                restoreSourceScopedState(playback.source)
                bindNativeQueue(playback, version)
                if (playback.positionMs > 0 && danDanPlayer.isSeekable()) danDanPlayer.seekTo(playback.positionMs)
            }

            override fun detach() {
                danDanPlayer.recordPlayInfo()
                check(danDanPlayer.release()) { "BackendDetachFailed" }
                media3SequentialPlaybackAdapter.onBackendDetached()
                nativeQueueToken = null
                videoController.release()
                videoSource = null
                preparingProgress = null
            }
        }
    }

    private val handoffPlayStateListener: (PlayState) -> Unit = { state ->
        if (state == PlayState.STATE_PLAYING) preparingProgress = null
        if (pauseAfterPrepare && state == PlayState.STATE_PLAYING) {
            pauseAfterPrepare = false
            danDanPlayer.pause()
        }
        if (state == PlayState.STATE_PAUSED) {
            // DanDanVideoPlayer dispatches PAUSED before its kernel.pause() call returns.
            danDanPlayer.post { if (!isDestroyed) viewModel.savePlaybackProgress() }
        }
        viewModel.refreshPlaybackState(playbackBackend)
    }

    // 电量管理
    /*
    private var batteryHelper = BatteryHelper()
     */
    private val media3SessionServiceProvider: Media3SessionServiceProvider? by lazy {
        ARouter.getInstance().navigation(Media3SessionServiceProvider::class.java)
    }
    private var media3ServiceBound = false

    private val exitConfirmGuard = BackPressExitConfirmGuard(BACK_EXIT_CONFIRM_WINDOW_MS)
    private val media3SequentialPlaybackAdapter: Media3SequentialPlaybackAdapter by lazy {
        Media3SequentialPlaybackAdapter.create { danDanPlayer.exoPlayerOrNull() }
    }
    private var nativeQueueToken: SequentialPlaybackSessionToken? = null

    private fun bindNativeQueue(
        playback: PreparedPlayback,
        version: PlaybackSessionVersion
    ) {
        val token = SequentialPlaybackSessionToken(version, playback.source.getUniqueKey(), PlayerInitializer.playerType)
        nativeQueueToken = token
        media3SequentialPlaybackAdapter.setEventListener(
            object : SequentialPlaybackBackendAdapter.EventListener {
                override fun onTransitionToQueuedItem(
                    sessionToken: SequentialPlaybackSessionToken,
                    queueItemId: SequentialPlaybackQueueItemId
                ) {
                    if (nativeQueueToken == sessionToken) viewModel.nativePlaybackTransition(queueItemId.value)
                }
            }
        )
        media3SequentialPlaybackAdapter.beginSession(token, playback.source)
    }

    private val sequentialPlaybackCoordinator: SequentialPlaybackCoordinator by lazy {
        SequentialPlaybackCoordinator(
            scope = lifecycleScope,
            sessionProvider = { viewModel.activePlaybackSession },
            supportsSeamlessPlayback = { danDanPlayer.exoPlayerOrNull() != null },
            switchTo = ::switchVideoSource,
            isAutoPlayNextEnabled = { PlayerInitializer.Player.isAutoPlayNext }
        )
    }
    private val sequentialPlayStateListener: (PlayState) -> Unit = { playState ->
        when (playState) {
            PlayState.STATE_PLAYING -> sequentialPlaybackCoordinator.onPlaybackStable()
            PlayState.STATE_ERROR,
            PlayState.STATE_IDLE,
            PlayState.STATE_START_ABORT
            -> sequentialPlaybackCoordinator.invalidateActiveSession()
            else -> Unit
        }
    }

    private val media3ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?
            ) {
                if (service is Media3SessionClient) media3ServiceBound = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                media3ServiceBound = false
            }
        }

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            PlayerViewModel::class.java
        )

    override fun getLayoutId() = R.layout.activity_player

    override fun onCreate(savedInstanceState: Bundle?) {
        activityArguments = PlaybackActivityArguments.read(intent, savedInstanceState)
        super.onCreate(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.deliverPlayback(intent.getStringExtra(PlaybackNavigation.REQUEST_ID))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!explicitPlaybackExit) {
            PlaybackActivityArguments.save(outState, intent.getStringExtra(PlaybackNavigation.REQUEST_ID), viewModel.playbackSessionId)
        }
    }

    override fun finish() {
        if (!explicitPlaybackExit) {
            explicitPlaybackExit = true
            intent.removeExtra(PlaybackNavigation.REQUEST_ID)
            viewModel.exitPlayback()
        }
        super.finish()
    }

    override fun initStatusBar() {
        StatusBarStyle.applyFullscreenHideAllBars(this)
    }

    override fun initView() {
        ARouter.getInstance().inject(this)

        LogFacade.d(
            LogModule.PLAYER,
            TAG_ACTIVITY,
            "initView start requestId=${activityArguments.requestId}"
        )

        registerReceiver()

        initPlayerConfig()

        initPlayer()

        initListener()

        danDanPlayer.addPlayStateListener(handoffPlayStateListener)
        danDanPlayer.seekCompleteListener = {
            val expected = viewModel.playbackVersion
            danDanPlayer.post {
                if (!isDestroyed && expected != null && viewModel.playbackVersion == expected) {
                    viewModel.savePlaybackProgress()
                }
            }
        }
        danDanPlayer.addPlayStateListener(sequentialPlayStateListener)
        danDanPlayer.setController(videoController)
        dataBinding.playerContainer.removeAllViews()
        dataBinding.playerContainer.addView(danDanPlayer)

        dataBinding.playerContainer.isSaveFromParentEnabled = false
        dataBinding.playerContainer.addView(preparationView)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.playbackScreen.collect { renderPlaybackScreen(it) } }
                launch { viewModel.recoverySaveFailure.collect { ToastCenter.showWarning("播放恢复信息暂未保存") } }
            }
        }
        viewModel.bindPlayback(playbackBackend, activityArguments)

        LogFacade.d(LogModule.PLAYER, TAG_ACTIVITY, "initView finished")
    }

    override fun onResume() {
        super.onResume()

        exitPopupMode()
        viewModel.refreshPlaybackState(playbackBackend)
    }

    override fun onStart() {
        super.onStart()
        val intent = media3SessionServiceProvider?.createBindIntent(this)
        if (intent == null) {
            LogFacade.w(LogModule.PLAYER, TAG_ACTIVITY, "Media3SessionServiceProvider not found")
            return
        }
        try {
            bindService(intent, media3ServiceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            LogFacade.w(LogModule.PLAYER, TAG_ACTIVITY, "bind Media3SessionService failed: ${e.message}")
        }
    }

    override fun onStop() {
        if (media3ServiceBound) {
            unbindService(media3ServiceConnection)
            media3ServiceBound = false
        }
        super.onStop()
    }

    override fun onPause() {
        exitConfirmGuard.reset()
        playingBeforeLifecyclePause = danDanPlayer.isPlaying()

        val popupNotShowing = popupManager.isShowing().not()
        val backgroundAllowed = PlayerConfig.isBackgroundPlay() && media3SupportsBackgroundPlayback()
        val backgroundPlayDisable = backgroundAllowed.not()
        LogFacade.d(
            LogModule.PLAYER,
            TAG_ACTIVITY,
            "onPause popupNotShowing=$popupNotShowing backgroundAllowed=$backgroundAllowed"
        )
        if (popupNotShowing && backgroundPlayDisable) {
            danDanPlayer.pause()
        }
        danDanPlayer.recordPlayInfo()
        viewModel.savePlaybackProgress()
        super.onPause()
    }

    override fun onDestroy() {
        LogFacade.d(LogModule.PLAYER, TAG_ACTIVITY, "onDestroy")
        sequentialPlaybackCoordinator.invalidateActiveSession()
        danDanPlayer.removePlayStateListener(sequentialPlayStateListener)
        danDanPlayer.removePlayStateListener(handoffPlayStateListener)
        danDanPlayer.seekCompleteListener = null
        beforePlayExit()
        unregisterReceiver()
        popupManager.dismiss()
        viewModel.unbindPlayback(playbackBackend)
        /*
        batteryHelper.release()
         */
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (danDanPlayer.onBackPressed()) {
            return
        }

        if (isTelevisionUiMode() && exitConfirmGuard.shouldInterceptExit()) {
            ToastCenter.showToast("再按一次退出播放")
            return
        }

        exitConfirmGuard.reset()
        exitPlayerByBack()
    }

    private fun exitPlayerByBack() {
        danDanPlayer.recordPlayInfo()
        finish()
    }

    private fun renderPlaybackScreen(state: PlaybackScreenState) {
        preparationView.visibility = if (state.preparing) View.VISIBLE else View.GONE
        if (state.preparing) {
            preparationView.text = if (state.hasPlayback) "正在准备新视频…" else "正在准备播放…"
            preparationView.setBackgroundColor(if (state.hasPlayback) 0xCC000000.toInt() else Color.BLACK)
            preparationView.layoutParams =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    if (state.hasPlayback) FrameLayout.LayoutParams.WRAP_CONTENT else FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.TOP
                )
        }
        val failure = state.failure ?: return
        viewModel.acknowledgePlaybackFailure(failure)
        ToastCenter.showError(failure.userMessage())
        if (!state.hasPlayback) finish()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean = danDanPlayer.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onScreenLocked() {
    }

    override fun onHeadsetRemoved() {
        danDanPlayer.pause()
    }

    override fun onSubtitleBackendFallback(
        reason: SubtitleFallbackReason,
        error: Throwable?
    ) {
        // Legacy backend switching is disabled; report the degraded libass state until recovery succeeds.
        val message =
            error?.message?.let { detail -> "libass subtitles degraded, recovery pending: $reason ($detail)" }
                ?: "libass subtitles degraded, recovery pending: $reason"
        LogFacade.w(LogModule.PLAYER, TAG_SUBTITLE, message)
    }

    private fun initListener() {
        danmuViewModel.loadDanmuLiveData.observe(this) { (videoUrl, matchDanmu) ->
            val curVideoSource = danDanPlayer.getVideoSource()
            val curVideoUrl = curVideoSource.getVideoUrl()
            if (curVideoUrl != videoUrl) {
                LogFacade.d(LogModule.PLAYER, TAG_DANMAKU, "skip load result mismatch url")
                return@observe
            }

            val trackHint =
                when (matchDanmu) {
                    is com.xyoye.data_component.bean.DanmuTrackResource.LocalFile -> "cid=${matchDanmu.danmu.episodeId}"
                }
            LogFacade.i(LogModule.PLAYER, TAG_DANMAKU, "match success $trackHint title=${curVideoSource.getVideoTitle()}")
            videoController.showMessage("匹配弹幕成功")
            videoController.addExtendTrack(VideoTrackBean.danmu(matchDanmu))
        }

        danmuViewModel.downloadDanmuLiveData.observe(this) { searchDanmu ->
            if (searchDanmu == null) {
                LogFacade.w(LogModule.PLAYER, TAG_DANMAKU, "download failed")
                videoController.showMessage("下载弹幕失败")
                return@observe
            }

            val episodeId =
                (searchDanmu as? com.xyoye.data_component.bean.DanmuTrackResource.LocalFile)?.danmu?.episodeId
            LogFacade.i(LogModule.PLAYER, TAG_DANMAKU, "download success id=$episodeId")
            videoController.showMessage("下载弹幕成功")
            videoController.addExtendTrack(VideoTrackBean.danmu(searchDanmu))
        }
    }

    private fun initPlayer() {
        videoController.apply {
            /*
            setBatteryHelper(batteryHelper)
             */

            // 播放错误
            observerPlayError {
                val source = danDanPlayer.getVideoSource()
                val playbackError =
                    danDanPlayer.getLastPlaybackErrorOrNull() ?: danDanPlayer.exoPlayerOrNull()?.playerError
                val baseMessage = "play error title=${source.getVideoTitle()} position=${danDanPlayer.getCurrentPosition()}"
                LogFacade.e(
                    LogModule.PLAYER,
                    TAG_PLAYBACK,
                    "$baseMessage ${PlaybackErrorFormatter.format(playbackError)}",
                    throwable = playbackError
                )
                showPlayErrorDialog()
            }
            // 退出播放
            observerExitPlayer {
                val source = danDanPlayer.getVideoSource()
                if (popupManager.isShowing()) {
                    LogFacade.d(LogModule.PLAYER, TAG_PLAYBACK, "exit from popup mode")
                    danDanPlayer.recordPlayInfo()
                }
                popupManager.dismiss()
                LogFacade.i(
                    LogModule.PLAYER,
                    TAG_PLAYBACK,
                    "exit player title=${source.getVideoTitle()}"
                )
                finish()
            }
            // 弹幕屏蔽
            observerDanmuBlock(
                cloudBlock = viewModel.cloudDanmuBlockLiveData,
                add = { keyword, isRegex -> viewModel.addDanmuBlock(keyword, isRegex) },
                remove = { id -> viewModel.removeDanmuBlock(id) },
                queryAll = { viewModel.localDanmuBlockLiveData }
            )
            // 弹幕搜索
            observerDanmuSearch(
                search = { danmuViewModel.searchDanmu(it) },
                download = { danmuViewModel.downloadDanmu(it) },
                searchResult = { danmuViewModel.danmuSearchLiveData }
            )
            // 进入悬浮窗模式
            observerEnterPopupMode {
                viewModel.dispatchPlayback(PlaybackAction.EnterPictureInPicture)
            }
            // 退出悬浮窗模式
            observerExitPopupMode {
                exitPopupMode()
                exitTaskBackground()
            }
            // 轨道添加完成
            observerTrackAdded {
                val source = videoSource ?: return@observerTrackAdded
                viewModel.storeTrackAdded(source, it)
            }
            observerPlaybackProgress { sequentialPlaybackCoordinator.onPlaybackProgress() }
            observerAutoPlayNext { nextIndex ->
                sequentialPlaybackCoordinator.onPlaybackCompletionRequested(nextIndex)
            }
        }
    }

    private fun updatePlayer(
        source: BaseVideoSource,
        restoredPositionMs: Long? = null
    ) {
        videoController.apply {
            setVideoTitle(source.getVideoTitle())
            val seekPosition = restoredPositionMs ?: source.getCurrentPosition()
            setLastPosition(seekPosition)
            setLastPlaySpeed(PlayerConfig.getNewVideoSpeed())
        }

        Media3Diagnostics.clearLastHttpOpen()

        danDanPlayer.apply {
            setVideoSource(source)
            start()
            LogFacade.i(
                LogModule.PLAYER,
                TAG_PLAYBACK,
                "start title=${source.getVideoTitle()} type=${source.getMediaType()} position=${source.getCurrentPosition()} speed=${PlayerConfig.getNewVideoSpeed()}"
            )
        }

        videoController.setSwitchVideoSourceBlock {
            LogFacade.i(LogModule.PLAYER, TAG_SOURCE, "switch request index=$it title=${source.getVideoTitle()}")
            switchVideoSource(it)
        }
    }

    private fun afterInitPlayer() {
        val source = videoSource ?: return
        restoreSourceScopedState(source)
    }

    private fun restoreSourceScopedState(source: BaseVideoSource) {
        videoController.clearSourceScopedState()

        // 设置当前视频的父目录（若为本地可访问路径），用于字幕/字体同目录检索
        File(source.getVideoUrl()).parentFile?.let { parent ->
            if (parent.exists()) {
                PlayerInitializer.selectSourceDirectory = parent.absolutePath
                LogFacade.d(
                    LogModule.PLAYER,
                    TAG_SOURCE,
                    "select directory set path=${parent.absolutePath} type=${source.getMediaType()}"
                )
            }
        }

        // 视频已绑定弹幕，直接加载，否则尝试匹配弹幕
        val historyDanmu = source.getDanmu()
        if (historyDanmu != null) {
            LogFacade.i(LogModule.PLAYER, TAG_DANMAKU, "load history cid=${historyDanmu.episodeId}")
            videoController.addExtendTrack(VideoTrackBean.danmu(historyDanmu))
        } else {
            val shouldAutoMatchDanmu = DanmuConfig.isAutoMatchDanmu() && source.getMediaType() != MediaType.FTP_SERVER
            if (shouldAutoMatchDanmu && popupManager.isShowing().not()) {
                LogFacade.i(LogModule.PLAYER, TAG_DANMAKU, "auto match start title=${source.getVideoTitle()}")
                danmuViewModel.matchDanmu(source)
            }
        }

        // 视频已绑定字幕，直接加载
        val historySubtitle = source.getSubtitlePath()
        if (historySubtitle != null) {
            LogFacade.i(LogModule.PLAYER, TAG_SUBTITLE, "load history path=$historySubtitle")
            videoController.addExtendTrack(VideoTrackBean.subtitle(historySubtitle))
        }

        // 视频已绑定音频，直接加载
        val historyAudio = source.getAudioPath()
        if (historyAudio != null) {
            LogFacade.i(LogModule.PLAYER, TAG_SOURCE, "load history path=$historyAudio")
            videoController.addExtendTrack(VideoTrackBean.audio(historyAudio))
        }
    }

    private fun registerReceiver() {
        LogFacade.d(LogModule.PLAYER, TAG_ACTIVITY, "register receivers")
        screenLockReceiver = ScreenBroadcastReceiver(this)
        headsetReceiver = HeadsetBroadcastReceiver(this)
        registerReceiver(screenLockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registerReceiver(headsetReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        if (exitPlayerReceiver == null) {
            exitPlayerReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?
                    ) {
                        val payload = intent ?: return
                        if (payload.action != PlayerActions.ACTION_EXIT_PLAYER) return

                        val storageId = payload.getIntExtra(PlayerActions.EXTRA_STORAGE_ID, -1)
                        if (storageId <= 0) return

                        val source = danDanPlayer.getVideoSource()
                        if (source.getStorageId() != storageId) return

                        danDanPlayer.recordPlayInfo()
                        danDanPlayer.pause()
                        finish()
                    }
                }
            val receiver = exitPlayerReceiver ?: return
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(PlayerActions.ACTION_EXIT_PLAYER),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        /*
        batteryHelper.registerReceiver(this)
         */
    }

    private fun unregisterReceiver() {
        if (this::screenLockReceiver.isInitialized) {
            unregisterReceiver(screenLockReceiver)
            LogFacade.d(LogModule.PLAYER, TAG_ACTIVITY, "unregister screen receiver")
        }
        if (this::headsetReceiver.isInitialized) {
            unregisterReceiver(headsetReceiver)
            LogFacade.d(LogModule.PLAYER, TAG_ACTIVITY, "unregister headset receiver")
        }
        exitPlayerReceiver?.let {
            unregisterReceiver(it)
            LogFacade.d(LogModule.PLAYER, TAG_ACTIVITY, "unregister exit player receiver")
        }
        exitPlayerReceiver = null
        /*
        batteryHelper.unregisterReceiver(this)
        LogFacade.d(LogModule.PLAYER, TAG_ACTIVITY, "unregister battery receiver")
         */
    }

    private fun initPlayerConfig(overrideProfile: PlaybackProfile? = null) {
        // 播放器类型
        val storedPlayerType = PlayerConfig.getUsePlayerType()
        val resolvedPlayerType = PlayerType.valueOf(storedPlayerType)
        if (resolvedPlayerType.value != storedPlayerType) {
            LogFacade.w(
                LogModule.PLAYER,
                TAG_CONFIG,
                "sanitize playerType stored=$storedPlayerType -> ${resolvedPlayerType.value}"
            )
            PlayerConfig.putUsePlayerType(resolvedPlayerType.value)
        }

        val globalProfile =
            PlaybackProfile(
                playerType = resolvedPlayerType,
                source = PlaybackProfileSource.GLOBAL
            )
        val sessionProfile =
            overrideProfile
                ?: globalProfile

        PlayerInitializer.playerType = sessionProfile.playerType
        LogFacade.d(
            LogModule.PLAYER,
            TAG_CONFIG,
            "playerType=${PlayerInitializer.playerType} source=${sessionProfile.source}"
        )
        // 是否使用SurfaceView
        PlayerInitializer.surfaceType =
            if (PlayerConfig.isUseSurfaceView()) SurfaceType.VIEW_SURFACE else SurfaceType.VIEW_TEXTURE
        // 视频速度
        PlayerInitializer.Player.videoSpeed = PlayerConfig.getNewVideoSpeed()
        // 长按视频速度
        PlayerInitializer.Player.pressVideoSpeed = PlayerConfig.getPressVideoSpeed()
        // 自动播放下一集
        PlayerInitializer.Player.isAutoPlayNext = PlayerConfig.isAutoPlayNext()

        PlayerInitializer.Player.vlcHWDecode =
            VLCHWDecode.valueOf(PlayerConfig.getUseVLCHWDecoder())
        val preferredAudioOutput = VLCAudioOutput.valueOf(PlayerConfig.getUseVLCAudioOutput())
        PlayerInitializer.Player.vlcAudioOutput = VlcAudioPolicy.resolveOutput(preferredAudioOutput)
        LogFacade.d(
            LogModule.PLAYER,
            TAG_CONFIG,
            "vlc hw=${PlayerInitializer.Player.vlcHWDecode} audio=${PlayerInitializer.Player.vlcAudioOutput} compat=${PlayerConfig.isVlcAudioCompatMode()}"
        )

        // 弹幕配置
        PlayerInitializer.Danmu.size = DanmuConfig.getDanmuSize()
        PlayerInitializer.Danmu.speed = DanmuConfig.getDanmuSpeed()
        PlayerInitializer.Danmu.alpha = DanmuConfig.getDanmuAlpha()
        PlayerInitializer.Danmu.stoke = DanmuConfig.getDanmuStoke()
        PlayerInitializer.Danmu.topDanmu = DanmuConfig.isShowTopDanmu()
        PlayerInitializer.Danmu.mobileDanmu = DanmuConfig.isShowMobileDanmu()
        PlayerInitializer.Danmu.bottomDanmu = DanmuConfig.isShowBottomDanmu()
        PlayerInitializer.Danmu.maxScrollLine = DanmuConfig.getDanmuScrollMaxLine()
        PlayerInitializer.Danmu.maxTopLine = DanmuConfig.getDanmuTopMaxLine()
        PlayerInitializer.Danmu.maxBottomLine = DanmuConfig.getDanmuBottomMaxLine()
        PlayerInitializer.Danmu.maxNum = DanmuConfig.getDanmuMaxCount()
        PlayerInitializer.Danmu.cloudBlock = DanmuConfig.isCloudDanmuBlock()
        PlayerInitializer.Danmu.updateInChoreographer = DanmuConfig.isDanmuUpdateInChoreographer()
        PlayerInitializer.Danmu.language = DanmakuLanguage.formValue(DanmuConfig.getDanmuLanguage())
        LogFacade.d(
            LogModule.PLAYER,
            TAG_CONFIG,
            "danmu size=${PlayerInitializer.Danmu.size} speed=${PlayerInitializer.Danmu.speed} language=${PlayerInitializer.Danmu.language}"
        )

        // 字幕配置
        PlayerInitializer.Subtitle.textSize = SubtitleConfig.getTextSize()
        PlayerInitializer.Subtitle.strokeWidth = SubtitleConfig.getStrokeWidth()
        PlayerInitializer.Subtitle.textColor = SubtitleConfig.getTextColor()
        PlayerInitializer.Subtitle.strokeColor = SubtitleConfig.getStrokeColor()
        PlayerInitializer.Subtitle.alpha = SubtitleConfig.getAlpha()
        PlayerInitializer.Subtitle.fontScaleOffsetPercent = SubtitleConfig.getSubtitleFontScaleOffsetPercent()
        PlayerInitializer.Subtitle.verticalOffset = SubtitleConfig.getVerticalOffset()
        val backend = SubtitleRendererBackend.LIBASS
        PlayerInitializer.Subtitle.backend = backend
        LogFacade.d(
            LogModule.PLAYER,
            TAG_CONFIG,
            "subtitle size=${PlayerInitializer.Subtitle.textSize} stroke=${PlayerInitializer.Subtitle.strokeWidth} backend=${PlayerInitializer.Subtitle.backend}"
        )
    }

    private fun showPlayErrorDialog() {
        val source = videoSource
        val isTorrentSource = source?.getMediaType() == MediaType.MAGNET_LINK

        val tips =
            if (source is StorageVideoSource && isTorrentSource) {
                val taskLog = PlayTaskBridge.getTaskLog(source.getPlayTaskId())
                "播放失败，资源已失效或暂时无法访问，请尝试切换资源$taskLog"
            } else {
                "播放失败，请尝试更改播放器设置，或者切换其它播放内核"
            }

        val builder =
            AlertDialog
                .Builder(this@PlayerActivity)
                .setTitle("错误")
                .setCancelable(false)
                .setMessage(tips)
                .setNegativeButton("退出播放") { dialog, _ ->
                    dialog.dismiss()
                    this@PlayerActivity.finish()
                }

        if (PlayerInitializer.playerType == PlayerType.TYPE_MPV_PLAYER) {
            builder.setPositiveButton("切换默认内核重试") { dialog, _ ->
                dialog.dismiss()
                val expected = viewModel.playbackVersion ?: return@setPositiveButton

                val fallbackProfile =
                    PlaybackProfile(
                        playerType = PlayerType.TYPE_EXO_PLAYER,
                        source = PlaybackProfileSource.FALLBACK
                    )
                showLoading()
                lifecycleScope.launch {
                    try {
                        val result = viewModel.fallbackPlaybackKernel(fallbackProfile, expected)
                        if (result !is SessionRequestResult.Activated && viewModel.playbackVersion == expected) showPlayErrorDialog()
                    } finally {
                        hideLoading()
                    }
                }
            }
        }

        if (isTorrentSource) {
            builder.setNeutralButton("播放器设置") { dialog, _ ->
                dialog.dismiss()
                ARouter
                    .getInstance()
                    .build(RouteTable.User.SettingPlayer)
                    .navigation()
                this@PlayerActivity.finish()
            }
        }

        builder.create().show()
    }

    private fun beforePlayExit() {
        sequentialPlaybackCoordinator.invalidateActiveSession()
    }

    private fun switchVideoSource(index: Int) {
        viewModel.selectQueueItem(index)
    }

    private fun media3SupportsBackgroundPlayback(): Boolean =
        viewModel.playbackState.value
            ?.backgroundModes
            ?.contains(Media3BackgroundMode.NOTIFICATION) == true

    private fun media3SupportsPip(): Boolean =
        viewModel.playbackState.value
            ?.backgroundModes
            ?.contains(Media3BackgroundMode.PIP) == true

    private fun enterPopupMode() {
        if (!media3SupportsPip()) {
            ToastCenter.showWarning("当前播放不支持画中画模式")
            return
        }
        if (popupManager.isShowing()) {
            return
        }
        currentFocus?.clearFocus()
        dataBinding.playerContainer.removeAllViews()
        popupManager.show(danDanPlayer)

        danDanPlayer.enterPopupMode()
    }

    private fun exitPopupMode() {
        if (popupManager.isShowing().not()) {
            return
        }
        popupManager.dismiss()

        dataBinding.playerContainer.removeAllViews()
        dataBinding.playerContainer.addView(danDanPlayer)
        danDanPlayer.requestFocus()

        danDanPlayer.exitPopupMode()
    }

    private fun enterTaskBackground() {
        moveTaskToBack(true)
    }

    private fun exitTaskBackground() {
        val intent =
            Intent(this, javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        startActivity(intent)
    }
}
