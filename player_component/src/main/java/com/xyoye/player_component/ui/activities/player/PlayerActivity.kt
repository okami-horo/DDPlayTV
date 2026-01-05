package com.xyoye.player_component.ui.activities.player

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.xyoye.player.utils.PlaybackErrorFormatter
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.gyf.immersionbar.BarHide
import com.gyf.immersionbar.ImmersionBar
import com.xyoye.common_component.bilibili.BilibiliKeys
import com.xyoye.common_component.bilibili.error.BilibiliPlaybackErrorReporter
import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackSession
import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackSessionStore
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.bridge.PlayTaskBridge
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.config.PlayerActions
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.media3.Media3SessionClient
import com.xyoye.common_component.receiver.HeadsetBroadcastReceiver
import com.xyoye.common_component.receiver.PlayerReceiverListener
import com.xyoye.common_component.receiver.ScreenBroadcastReceiver
import com.xyoye.common_component.source.VideoSourceManager
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.utils.screencast.ScreencastHandler
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.media3.Media3BackgroundMode
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.data_component.enums.DanmakuLanguage
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayState
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.SubtitleFallbackReason
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.data_component.enums.VLCAudioOutput
import com.xyoye.data_component.enums.VLCHWDecode
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player.controller.VideoController
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.impl.media3.Media3Diagnostics
import com.xyoye.player.kernel.impl.vlc.VlcAudioPolicy
import com.xyoye.player.subtitle.backend.SubtitleFallbackDispatcher
import com.xyoye.player_component.BR
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ActivityPlayerBinding
import com.xyoye.player_component.widgets.popup.PlayerPopupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@UnstableApi
@Route(path = RouteTable.Player.PlayerCenter)
class PlayerActivity :
    BaseActivity<PlayerViewModel, ActivityPlayerBinding>(),
    PlayerReceiverListener,
    ScreencastHandler,
    SubtitleFallbackDispatcher {
    companion object {
        private const val MEDIA3_SESSION_SERVICE =
            "com.xyoye.dandanplay.app.service.Media3SessionService"
        private const val TAG_ACTIVITY = "PlayerActivity"
        private const val TAG_SOURCE = "PlayerSource"
        private const val TAG_PLAYBACK = "PlayerPlayback"
        private const val TAG_DANMAKU = "PlayerDanmaku"
        private const val TAG_SUBTITLE = "PlayerSubtitle"
        private const val TAG_CONFIG = "PlayerConfig"
        private const val TAG_CAST = "PlayerCast"
        private const val TAG_MEDIA3 = "Media3Session"

        private const val BILIBILI_LIVE_COMPLETION_REPORT_MIN_WATCH_MS = 5 * 60_000L
    }

    private val danmuViewModel: PlayerDanmuViewModel by lazy {
        ViewModelProvider(
            viewModelStore,
            ViewModelProvider.AndroidViewModelFactory(application),
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

    // 电量管理
    /*
    private var batteryHelper = BatteryHelper()
     */
    private var media3SessionClient: Media3SessionClient? = null
    private var media3ServiceBound = false
    private var media3BackgroundModes: Set<Media3BackgroundMode> = emptySet()
    private var media3BackgroundJob: Job? = null
    private var latestMedia3Session: PlaybackSession? = null
    private var latestMedia3Capability: PlayerCapabilityContract? = null

    private var bilibiliLiveSessionKey: String? = null
    private var bilibiliLiveSessionStartElapsedMs: Long? = null
    private var bilibiliLiveCompletionReported: Boolean = false
    private var bilibiliLiveErrorReported: Boolean = false
    private var bilibiliRecoverJob: Job? = null
    private var bilibiliRecoverAttempts: Int = 0
    private var pendingSeekPositionMs: Long? = null

    private val media3ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?
            ) {
                val client = service as? Media3SessionClient ?: return
                media3SessionClient = client
                media3ServiceBound = true
                pushMedia3SessionToService()
                media3BackgroundJob =
                    lifecycleScope.launch {
                        client.backgroundModes().collectLatest { modes ->
                            updateBackgroundModes(modes)
                        }
                    }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                media3ServiceBound = false
                media3SessionClient = null
                media3BackgroundJob?.cancel()
                media3BackgroundJob = null
                media3BackgroundModes = emptySet()
            }
        }

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            PlayerViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_player

    override fun initStatusBar() {
        ImmersionBar
            .with(this)
            .fullScreen(true)
            .hideBar(BarHide.FLAG_HIDE_BAR)
            .init()
    }

    override fun initView() {
        ARouter.getInstance().inject(this)

        LogFacade.d(
            LogModule.PLAYER,
            TAG_ACTIVITY,
            "initView start intent=${intent?.action.orEmpty()} source=${VideoSourceManager
                .getInstance()
                .getSource()
                ?.javaClass
                ?.simpleName ?: "null"}",
        )

        registerReceiver()

        initPlayerConfig()

        initPlayer()

        viewModel.media3SessionLiveData.observe(this) { session ->
            latestMedia3Session = session
            pushMedia3SessionToService()
        }

        viewModel.media3CapabilityLiveData.observe(this) { capability ->
            latestMedia3Capability = capability
            pushMedia3SessionToService()
        }

        initListener()

        danDanPlayer.setController(videoController)
        dataBinding.playerContainer.removeAllViews()
        dataBinding.playerContainer.addView(danDanPlayer)

        applyPlaySource(VideoSourceManager.getInstance().getSource())

        LogFacade.d(LogModule.PLAYER, TAG_ACTIVITY, "initView finished")
    }

    override fun onResume() {
        super.onResume()

        exitPopupMode()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent().setClassName(this, MEDIA3_SESSION_SERVICE)
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
        media3SessionClient = null
        media3BackgroundJob?.cancel()
        media3BackgroundJob = null
        media3BackgroundModes = emptySet()
        super.onStop()
    }

    override fun onPause() {
        val popupNotShowing = popupManager.isShowing().not()
        val backgroundAllowed = PlayerConfig.isBackgroundPlay() && media3SupportsBackgroundPlayback()
        val backgroundPlayDisable = backgroundAllowed.not()
        LogFacade.d(
            LogModule.PLAYER,
            TAG_ACTIVITY,
            "onPause popupNotShowing=$popupNotShowing backgroundAllowed=$backgroundAllowed",
        )
        if (popupNotShowing && backgroundPlayDisable) {
            danDanPlayer.pause()
        }
        danDanPlayer.recordPlayInfo()
        super.onPause()
    }

    override fun onDestroy() {
        LogFacade.d(LogModule.PLAYER, TAG_ACTIVITY, "onDestroy")
        beforePlayExit()
        unregisterReceiver()
        danDanPlayer.release()
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
        danDanPlayer.recordPlayInfo()
        finish()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean = danDanPlayer.onKeyDown(keyCode, event) or super.onKeyDown(keyCode, event)

    override fun onScreenLocked() {
    }

    override fun onHeadsetRemoved() {
        danDanPlayer.pause()
    }

    override fun onSubtitleBackendFallback(
        reason: SubtitleFallbackReason,
        error: Throwable?
    ) {
        // Legacy backend switching is disabled; keep libass active.
        val message =
            error?.message?.let { detail -> "libass fallback ignored: $reason ($detail)" }
                ?: "libass fallback ignored: $reason"
        LogFacade.w(LogModule.PLAYER, TAG_SUBTITLE, message)
    }

    override fun playScreencast(videoSource: BaseVideoSource) {
        LogFacade.i(
            LogModule.PLAYER,
            TAG_CAST,
            "receive screencast title=${videoSource.getVideoTitle()} type=${videoSource.getMediaType()}",
        )
        lifecycleScope.launch(Dispatchers.Main) {
            applyPlaySource(videoSource)
        }
    }

    private fun checkPlayParams(source: BaseVideoSource?): Boolean {
        if (source == null || source.getVideoUrl().isEmpty()) {
            LogFacade.w(
                LogModule.PLAYER,
                TAG_SOURCE,
                "invalid source url=${source?.getVideoUrl()} type=${source?.getMediaType()}",
            )
            CommonDialog
                .Builder(this)
                .run {
                    content = "解析播放参数失败"
                    addPositive("退出重试") {
                        it.dismiss()
                        finish()
                    }
                    build()
                }.show()
            return false
        }

        return true
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
                    is com.xyoye.data_component.bean.DanmuTrackResource.BilibiliLive -> "live roomId=${matchDanmu.roomId}"
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
                reportBilibiliPlaybackErrorIfNeeded(
                    source = source,
                    throwable = playbackError,
                    scene = "play_error",
                )

                val isBilibiliSource = BilibiliPlaybackErrorReporter.isBilibiliSource(source.getMediaType())
                val baseMessage = "play error title=${source.getVideoTitle()} position=${danDanPlayer.getCurrentPosition()}"
                if (isBilibiliSource) {
                    LogFacade.e(LogModule.PLAYER, TAG_PLAYBACK, baseMessage)
                } else {
                    LogFacade.e(
                        LogModule.PLAYER,
                        TAG_PLAYBACK,
                        "$baseMessage ${PlaybackErrorFormatter.format(playbackError)}",
                        throwable = playbackError,
                    )
                }
                if (tryRecoverBilibiliPlayback(source, playbackError)) {
                    return@observerPlayError
                }
                showPlayErrorDialog()
            }
            // 退出播放
            observerExitPlayer {
                val source = danDanPlayer.getVideoSource()
                reportBilibiliLiveCompletionIfNeeded(source)
                if (popupManager.isShowing()) {
                    LogFacade.d(LogModule.PLAYER, TAG_PLAYBACK, "exit from popup mode")
                    danDanPlayer.recordPlayInfo()
                }
                popupManager.dismiss()
                LogFacade.i(
                    LogModule.PLAYER,
                    TAG_PLAYBACK,
                    "exit player title=${source.getVideoTitle()}",
                )
                finish()
            }
            // 弹幕屏蔽
            observerDanmuBlock(
                cloudBlock = viewModel.cloudDanmuBlockLiveData,
                add = { keyword, isRegex -> viewModel.addDanmuBlock(keyword, isRegex) },
                remove = { id -> viewModel.removeDanmuBlock(id) },
                queryAll = { viewModel.localDanmuBlockLiveData },
            )
            // 弹幕搜索
            observerDanmuSearch(
                search = { danmuViewModel.searchDanmu(it) },
                download = { danmuViewModel.downloadDanmu(it) },
                searchResult = { danmuViewModel.danmuSearchLiveData },
            )
            // 进入悬浮窗模式
            observerEnterPopupMode {
                enterPopupMode()
                enterTaskBackground()
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

            // B站画质/编码切换
            observerBilibiliPlaybackUpdate { update ->
                val source = danDanPlayer.getVideoSource()
                if (!BilibiliPlaybackErrorReporter.isBilibiliSource(source.getMediaType())) {
                    return@observerBilibiliPlaybackUpdate
                }
                if (BilibiliPlaybackErrorReporter.isBilibiliLive(source.getMediaType(), source.getUniqueKey())) {
                    return@observerBilibiliPlaybackUpdate
                }

                val storageSource = source as? StorageVideoSource ?: return@observerBilibiliPlaybackUpdate
                val session =
                    BilibiliPlaybackSessionStore.get(storageSource.getStorageId(), storageSource.getUniqueKey())
                        ?: run {
                            ToastCenter.showWarning("未获取到B站播放会话")
                            return@observerBilibiliPlaybackUpdate
                        }

                val positionMs = danDanPlayer.getCurrentPosition()
                bilibiliRecoverJob?.cancel()
                showLoading()
                danDanPlayer.pause()
                bilibiliRecoverJob =
                    lifecycleScope.launch(Dispatchers.IO) {
                        val result = session.applyPreferenceUpdate(update, positionMs)
                        withContext(Dispatchers.Main) {
                            hideLoading()
                            val playUrl = result.getOrNull()
                            if (playUrl.isNullOrBlank()) {
                                ToastCenter.showError("切换失败")
                                return@withContext
                            }

                            val newSource = rebuildStorageVideoSource(storageSource, playUrl)
                            pendingSeekPositionMs = positionMs
                            applyPlaySource(newSource)
                        }
                    }
            }
        }
    }

    private fun applyPlaySource(newSource: BaseVideoSource?) {
        LogFacade.i(
            LogModule.PLAYER,
            TAG_SOURCE,
            "apply start title=${newSource?.getVideoTitle()} type=${newSource?.getMediaType()} urlHash=${newSource?.getVideoUrl()?.hashCode()}",
        )
        val previousSource = videoSource
        if (previousSource != null &&
            newSource != null &&
            (previousSource.getStorageId() != newSource.getStorageId() || previousSource.getUniqueKey() != newSource.getUniqueKey()) &&
            BilibiliPlaybackErrorReporter.isBilibiliSource(previousSource.getMediaType())
        ) {
            BilibiliPlaybackSessionStore.remove(previousSource.getStorageId(), previousSource.getUniqueKey())
        }

        bilibiliRecoverJob?.cancel()
        bilibiliRecoverJob = null
        bilibiliRecoverAttempts = 0
        danDanPlayer.recordPlayInfo()
        danDanPlayer.pause()
        danDanPlayer.release()
        videoController.release()

        videoSource = newSource
        if (checkPlayParams(videoSource).not()) {
            LogFacade.w(LogModule.PLAYER, TAG_SOURCE, "apply abort invalid source")
            return
        }
        VideoSourceManager.getInstance().setSource(videoSource!!)

        val overrideParams = VideoSourceManager.getInstance().consumeMedia3LaunchParams()
        val launchParams = viewModel.buildMedia3LaunchParams(videoSource!!, overrideParams)
        viewModel.prepareMedia3Session(launchParams)

        updatePlayer(videoSource!!)

        afterInitPlayer()
        LogFacade.i(LogModule.PLAYER, TAG_SOURCE, "apply finished title=${videoSource?.getVideoTitle()}")
    }

    private fun updatePlayer(source: BaseVideoSource) {
        videoController.apply {
            setVideoTitle(source.getVideoTitle())
            val seekPosition =
                pendingSeekPositionMs
                    ?.takeIf { it > 0 }
                    ?: source.getCurrentPosition()
            pendingSeekPositionMs = null
            setLastPosition(seekPosition)
            setLastPlaySpeed(PlayerConfig.getNewVideoSpeed())
        }

        updateBilibiliLiveSession(source)

        danDanPlayer.apply {
            setVideoSource(source)
            start()
            LogFacade.i(
                LogModule.PLAYER,
                TAG_PLAYBACK,
                "start title=${source.getVideoTitle()} type=${source.getMediaType()} position=${source.getCurrentPosition()} speed=${PlayerConfig.getNewVideoSpeed()}",
            )
        }
        /*
        //发送弹幕
        videoController.observerSendDanmu {
            LogFacade.d(LogModule.PLAYER, TAG_DANMAKU, "send request text=${it.text}")
            viewModel.sendDanmu(source.getDanmu(), it)
        }
         */

        videoController.setSwitchVideoSourceBlock {
            LogFacade.i(LogModule.PLAYER, TAG_SOURCE, "switch request index=$it title=${source.getVideoTitle()}")
            switchVideoSource(it)
        }
    }

    private fun afterInitPlayer() {
        val source = videoSource ?: return

        // 设置当前视频的父目录（若为本地可访问路径），用于字幕/字体同目录检索
        File(source.getVideoUrl()).parentFile?.let { parent ->
            if (parent.exists()) {
                PlayerInitializer.selectSourceDirectory = parent.absolutePath
                LogFacade.d(
                    LogModule.PLAYER,
                    TAG_SOURCE,
                    "select directory set path=${parent.absolutePath} type=${source.getMediaType()}",
                )
            }
        }

        // 视频已绑定弹幕，直接加载，否则尝试匹配弹幕
        val historyDanmu = source.getDanmu()
        if (historyDanmu != null) {
            LogFacade.i(LogModule.PLAYER, TAG_DANMAKU, "load history cid=${historyDanmu.episodeId}")
            videoController.addExtendTrack(VideoTrackBean.danmu(historyDanmu))
        } else {
            val isBilibiliLive =
                source.getMediaType() == MediaType.BILIBILI_STORAGE &&
                    (BilibiliKeys.parse(source.getUniqueKey()) is BilibiliKeys.LiveKey)

            if (
                isBilibiliLive &&
                DanmuConfig.isAutoEnableBilibiliLiveDanmaku() &&
                popupManager.isShowing().not()
            ) {
                LogFacade.i(LogModule.PLAYER, TAG_DANMAKU, "auto live danmaku start title=${source.getVideoTitle()}")
                danmuViewModel.matchDanmu(source)
            } else if (
                DanmuConfig.isAutoMatchDanmu() &&
                source.getMediaType() != MediaType.FTP_SERVER &&
                popupManager.isShowing().not()
            ) {
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
                        if (source.getMediaType() != MediaType.BILIBILI_STORAGE) return
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
                ContextCompat.RECEIVER_NOT_EXPORTED,
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
                "sanitize playerType stored=$storedPlayerType -> ${resolvedPlayerType.value}",
            )
            PlayerConfig.putUsePlayerType(resolvedPlayerType.value)
        }

        val globalProfile =
            PlaybackProfile(
                playerType = resolvedPlayerType,
                source = PlaybackProfileSource.GLOBAL,
            )
        val sessionProfile =
            overrideProfile
                ?: (VideoSourceManager.getInstance().getSource() as? StorageVideoSource)
                    ?.getPlaybackProfile()
                ?: globalProfile

        PlayerInitializer.playerType = sessionProfile.playerType
        LogFacade.d(
            LogModule.PLAYER,
            TAG_CONFIG,
            "playerType=${PlayerInitializer.playerType} source=${sessionProfile.source}",
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
            "vlc hw=${PlayerInitializer.Player.vlcHWDecode} audio=${PlayerInitializer.Player.vlcAudioOutput} compat=${PlayerConfig.isVlcAudioCompatMode()}",
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
            "danmu size=${PlayerInitializer.Danmu.size} speed=${PlayerInitializer.Danmu.speed} language=${PlayerInitializer.Danmu.language}",
        )

        // 字幕配置
        PlayerInitializer.Subtitle.textSize = SubtitleConfig.getTextSize()
        PlayerInitializer.Subtitle.strokeWidth = SubtitleConfig.getStrokeWidth()
        PlayerInitializer.Subtitle.textColor = SubtitleConfig.getTextColor()
        PlayerInitializer.Subtitle.strokeColor = SubtitleConfig.getStrokeColor()
        PlayerInitializer.Subtitle.alpha = SubtitleConfig.getAlpha()
        PlayerInitializer.Subtitle.verticalOffset = SubtitleConfig.getVerticalOffset()
        val backend = SubtitleRendererBackend.LIBASS
        PlayerInitializer.Subtitle.backend = backend
        LogFacade.d(
            LogModule.PLAYER,
            TAG_CONFIG,
            "subtitle size=${PlayerInitializer.Subtitle.textSize} stroke=${PlayerInitializer.Subtitle.strokeWidth} backend=${PlayerInitializer.Subtitle.backend}",
        )
    }

    private fun updateBilibiliLiveSession(source: BaseVideoSource) {
        if (!BilibiliPlaybackErrorReporter.isBilibiliLive(source.getMediaType(), source.getUniqueKey())) {
            bilibiliLiveSessionKey = null
            bilibiliLiveSessionStartElapsedMs = null
            bilibiliLiveCompletionReported = false
            bilibiliLiveErrorReported = false
            return
        }

        bilibiliLiveSessionKey = source.getUniqueKey()
        bilibiliLiveSessionStartElapsedMs = SystemClock.elapsedRealtime()
        bilibiliLiveCompletionReported = false
        bilibiliLiveErrorReported = false
        Media3Diagnostics.clearLastHttpOpen()
    }

    private fun reportBilibiliPlaybackErrorIfNeeded(
        source: BaseVideoSource,
        throwable: Throwable?,
        scene: String,
    ) {
        val snapshot = source.toBilibiliPlaybackSourceSnapshot()

        if (!BilibiliPlaybackErrorReporter.isBilibiliSource(snapshot)) {
            return
        }

        val isLive = BilibiliPlaybackErrorReporter.isBilibiliLive(snapshot)
        if (isLive && bilibiliLiveErrorReported) {
            return
        }
        if (isLive) {
            bilibiliLiveErrorReported = true
        }

        val extra = buildBilibiliPlaybackExtra(source, throwable)
        BilibiliPlaybackErrorReporter.reportPlaybackError(
            source = snapshot,
            throwable = throwable,
            scene = scene,
            extra = extra,
        )
    }

    private fun reportBilibiliLiveCompletionIfNeeded(source: BaseVideoSource) {
        val snapshot = source.toBilibiliPlaybackSourceSnapshot()

        if (!BilibiliPlaybackErrorReporter.isBilibiliLive(snapshot)) {
            return
        }
        if (bilibiliLiveCompletionReported) {
            return
        }

        val playState = danDanPlayer.getPlayState()
        if (playState != PlayState.STATE_COMPLETED) {
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val watchElapsedMs =
            bilibiliLiveSessionStartElapsedMs
                ?.takeIf { bilibiliLiveSessionKey == source.getUniqueKey() }
                ?.let { nowElapsed - it }
        val isLiveFlag = danDanPlayer.isLive()
        val shouldReport =
            isLiveFlag || (watchElapsedMs != null && watchElapsedMs >= BILIBILI_LIVE_COMPLETION_REPORT_MIN_WATCH_MS)

        if (!shouldReport) {
            return
        }

        bilibiliLiveCompletionReported = true
        val extra =
            buildBilibiliPlaybackExtra(source, throwable = null).toMutableMap().apply {
                put("watchElapsedMs", watchElapsedMs?.toString().orEmpty())
                put("isLiveFlag", isLiveFlag.toString())
                put("playState", playState.name)
            }

        BilibiliPlaybackErrorReporter.reportUnexpectedCompletion(
            source = snapshot,
            scene = "live_completed",
            extra = extra,
        )
    }

    private fun BaseVideoSource.toBilibiliPlaybackSourceSnapshot(): BilibiliPlaybackErrorReporter.SourceSnapshot =
        BilibiliPlaybackErrorReporter.SourceSnapshot(
            mediaType = getMediaType(),
            storageId = getStorageId(),
            storagePath = getStoragePath(),
            uniqueKey = getUniqueKey(),
            videoTitle = getVideoTitle(),
            videoUrl = getVideoUrl(),
            httpHeader = getHttpHeader(),
        )

    private fun buildBilibiliPlaybackExtra(
        source: BaseVideoSource,
        throwable: Throwable?,
    ): Map<String, String> {
        val extra = linkedMapOf<String, String>()
        extra["playerType"] = PlayerInitializer.playerType.name
        extra["playState"] = danDanPlayer.getPlayState().name
        extra["positionMs"] = danDanPlayer.getCurrentPosition().toString()
        extra["durationMs"] = danDanPlayer.getDuration().toString()
        extra["bufferedPct"] = danDanPlayer.getBufferedPercentage().toString()
        extra["isLiveFlag"] = danDanPlayer.isLive().toString()
        extra["videoTitle"] = source.getVideoTitle()

        latestMedia3Session?.let {
            extra["media3.sessionId"] = it.sessionId
            extra["media3.mediaId"] = it.mediaId
            extra["media3.playerEngine"] = it.playerEngine.name
            extra["media3.sourceType"] = it.sourceType.name
            extra["media3.toggleCohort"] = it.toggleCohort?.name ?: "UNKNOWN"
        }

        Media3Diagnostics.snapshotLastHttpOpen()?.let {
            extra["lastHttpUrl"] = it.url.orEmpty()
            extra["lastHttpContentType"] = it.contentType.orEmpty()
            extra["lastHttpTimestampMs"] = it.timestampMs.toString()
            it.code?.let { code -> extra["httpResponseCode"] = code.toString() }
        }

        if (throwable is PlaybackException) {
            extra["media3.errorCode"] = throwable.errorCode.toString()
            extra["media3.errorCodeName"] = throwable.errorCodeName
        }

        findInvalidResponseCodeException(throwable)?.let { invalid ->
            extra["httpResponseCode"] = invalid.responseCode.toString()
        }

        return extra
    }

    private fun findInvalidResponseCodeException(throwable: Throwable?): HttpDataSource.InvalidResponseCodeException? {
        var current = throwable
        var depth = 0
        while (current != null && depth < 8) {
            if (current is HttpDataSource.InvalidResponseCodeException) {
                return current
            }
            current = current.cause
            depth++
        }
        return null
    }

    private fun findPlaybackException(throwable: Throwable?): PlaybackException? {
        var current = throwable
        var depth = 0
        while (current != null && depth < 8) {
            if (current is PlaybackException) {
                return current
            }
            current = current.cause
            depth++
        }
        return null
    }

    private fun tryRecoverBilibiliPlayback(
        source: BaseVideoSource,
        playbackError: Throwable?,
    ): Boolean {
        if (!BilibiliPlaybackErrorReporter.isBilibiliSource(source.getMediaType())) {
            return false
        }
        if (BilibiliPlaybackErrorReporter.isBilibiliLive(source.getMediaType(), source.getUniqueKey())) {
            return false
        }

        val storageSource = source as? StorageVideoSource ?: return false
        val session =
            BilibiliPlaybackSessionStore.get(storageSource.getStorageId(), storageSource.getUniqueKey())
                ?: return false

        if (bilibiliRecoverJob?.isActive == true) {
            return true
        }
        if (bilibiliRecoverAttempts >= 3) {
            return false
        }
        bilibiliRecoverAttempts += 1

        val positionMs = danDanPlayer.getCurrentPosition()
        val context = buildBilibiliRecoveryContext(playbackError)

        showLoading()
        danDanPlayer.pause()
        bilibiliRecoverJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val result = session.recover(context, positionMs)
                withContext(Dispatchers.Main) {
                    hideLoading()
                    val playUrl = result.getOrNull()
                    if (playUrl.isNullOrBlank()) {
                        showPlayErrorDialog()
                        return@withContext
                    }

                    val newSource = rebuildStorageVideoSource(storageSource, playUrl)
                    pendingSeekPositionMs = positionMs
                    bilibiliRecoverAttempts = 0
                    videoController.showMessage("已自动恢复播放")
                    applyPlaySource(newSource)
                }
            }

        return true
    }

    private fun buildBilibiliRecoveryContext(playbackError: Throwable?): BilibiliPlaybackSession.FailureContext {
        val invalid = findInvalidResponseCodeException(playbackError)
        val snapshot = Media3Diagnostics.snapshotLastHttpOpen()

        val failingUrl =
            snapshot?.url
                ?: invalid?.dataSpec?.uri?.toString()
        val responseCode =
            invalid?.responseCode
                ?: snapshot?.code

        val playbackException = findPlaybackException(playbackError)
        val isDecoderError =
            playbackException?.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED

        return BilibiliPlaybackSession.FailureContext(
            failingUrl = failingUrl,
            httpResponseCode = responseCode,
            isDecoderError = isDecoderError,
        )
    }

    private fun rebuildStorageVideoSource(
        source: StorageVideoSource,
        playUrl: String,
    ): StorageVideoSource {
        val storageFile = source.getStorageFile()
        val videoSources =
            (0 until source.getGroupSize())
                .map { index -> source.indexStorageFile(index) }
        return StorageVideoSource(
            playUrl = playUrl,
            file = storageFile,
            videoSources = videoSources,
            danmu = source.getDanmu(),
            subtitlePath = source.getSubtitlePath(),
            audioPath = source.getAudioPath(),
            playbackProfile = source.getPlaybackProfile(),
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
                val storageSource = videoSource as? StorageVideoSource
                if (storageSource == null) {
                    this@PlayerActivity.finish()
                    return@setPositiveButton
                }

                val fallbackProfile =
                    PlaybackProfile(
                        playerType = PlayerType.TYPE_EXO_PLAYER,
                        source = PlaybackProfileSource.FALLBACK,
                    )
                initPlayerConfig(overrideProfile = fallbackProfile)

                showLoading()
                danDanPlayer.pause()
                lifecycleScope.launch(Dispatchers.IO) {
                    val newSource =
                        StorageVideoSourceFactory.create(storageSource.getStorageFile(), fallbackProfile)
                            ?.also {
                                it.setDanmu(storageSource.getDanmu())
                                it.setSubtitlePath(storageSource.getSubtitlePath())
                                it.setAudioPath(storageSource.getAudioPath())
                            }
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        if (newSource == null) {
                            ToastCenter.showError("重试失败，找不到播放资源")
                            this@PlayerActivity.finish()
                            return@withContext
                        }
                        applyPlaySource(newSource)
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
        val source = videoSource ?: return
        if (source is StorageVideoSource && source.getMediaType() == MediaType.MAGNET_LINK) {
            PlayTaskBridge.sendTaskRemoveMsg(source.getPlayTaskId())
        }
        if (BilibiliPlaybackErrorReporter.isBilibiliSource(source.getMediaType())) {
            BilibiliPlaybackSessionStore.remove(source.getStorageId(), source.getUniqueKey())
        }
        bilibiliRecoverJob?.cancel()
        bilibiliRecoverJob = null
    }

    private fun switchVideoSource(index: Int) {
        showLoading()
        danDanPlayer.pause()
        lifecycleScope.launch(Dispatchers.IO) {
            val targetSource = videoSource?.indexSource(index)
            if (targetSource == null) {
                ToastCenter.showOriginalToast("播放资源不存在")
                return@launch
            }
            withContext(Dispatchers.Main) {
                hideLoading()
                applyPlaySource(targetSource)
            }
        }
    }

    private fun pushMedia3SessionToService() {
        media3SessionClient?.updateSession(latestMedia3Session, latestMedia3Capability)
    }

    private fun updateBackgroundModes(modes: Set<Media3BackgroundMode>) {
        media3BackgroundModes = modes
    }

    private fun media3SupportsBackgroundPlayback(): Boolean = media3BackgroundModes.contains(Media3BackgroundMode.NOTIFICATION)

    private fun media3SupportsPip(): Boolean = media3BackgroundModes.contains(Media3BackgroundMode.PIP)

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
