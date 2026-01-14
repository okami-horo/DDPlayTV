package com.xyoye.player_component.ui.activities.player

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.IBinder
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.gyf.immersionbar.BarHide
import com.gyf.immersionbar.ImmersionBar
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
import com.xyoye.common_component.playback.addon.PlaybackEvent
import com.xyoye.common_component.playback.addon.PlaybackIdentity
import com.xyoye.common_component.playback.addon.PlaybackPreferenceSwitchableAddon
import com.xyoye.common_component.playback.addon.PlaybackRecoveryRequest
import com.xyoye.common_component.playback.addon.PlaybackReleasableAddon
import com.xyoye.common_component.playback.addon.PlaybackUrlRecoverableAddon
import com.xyoye.common_component.receiver.HeadsetBroadcastReceiver
import com.xyoye.common_component.receiver.PlayerReceiverListener
import com.xyoye.common_component.receiver.ScreenBroadcastReceiver
import com.xyoye.common_component.source.VideoSourceManager
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.utils.danmu.StorageDanmuMatcher
import com.xyoye.common_component.utils.screencast.ScreencastHandler
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.entity.media3.Media3BackgroundMode
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.data_component.enums.DanmakuLanguage
import com.xyoye.data_component.enums.MediaType
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
import com.xyoye.player.utils.PlaybackErrorFormatter
import com.xyoye.player_component.BR
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ActivityPlayerBinding
import com.xyoye.player_component.widgets.popup.PlayerPopupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
            "com.okamihoro.ddplaytv.app.service.Media3SessionService"
        private const val TAG_ACTIVITY = "PlayerActivity"
        private const val TAG_SOURCE = "PlayerSource"
        private const val TAG_PLAYBACK = "PlayerPlayback"
        private const val TAG_DANMAKU = "PlayerDanmaku"
        private const val TAG_SUBTITLE = "PlayerSubtitle"
        private const val TAG_CONFIG = "PlayerConfig"
        private const val TAG_CAST = "PlayerCast"
        private const val TAG_MEDIA3 = "Media3Session"
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

    private var playbackAddonJob: Job? = null
    private var playbackRecoveryAttempts: Int = 0
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
                dispatchPlaybackErrorEvent(
                    source = source,
                    throwable = playbackError,
                    scene = "play_error",
                )

                val isBilibiliSource = source.getMediaType() == MediaType.BILIBILI_STORAGE
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
                if (tryRecoverPlayback(source, playbackError)) {
                    return@observerPlayError
                }
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
            observerPlaybackSettingUpdate { update ->
                val source = danDanPlayer.getVideoSource()
                val storageSource = source as? StorageVideoSource ?: return@observerPlaybackSettingUpdate
                val addon = storageSource.getPlaybackAddon() as? PlaybackPreferenceSwitchableAddon
                if (addon == null) {
                    ToastCenter.showWarning("当前播放源不支持切换")
                    return@observerPlaybackSettingUpdate
                }

                val positionMs = danDanPlayer.getCurrentPosition()
                if (PlayerInitializer.playerType != PlayerType.TYPE_EXO_PLAYER) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val result = addon.applySettingUpdate(update, positionMs)
                        withContext(Dispatchers.Main) {
                            if (result.isFailure) {
                                ToastCenter.showError(result.exceptionOrNull()?.message ?: "设置失败")
                            } else {
                                ToastCenter.showInfo("已保存设置，重新播放后生效")
                            }
                        }
                    }
                    return@observerPlaybackSettingUpdate
                }
                cancelPlaybackAddonJob(resetAttempts = false)
                showLoading()
                danDanPlayer.pause()
                playbackAddonJob =
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val result = addon.applySettingUpdate(update, positionMs)
                            withContext(Dispatchers.Main) {
                                hideLoading()
                                val playUrl = result.getOrNull()
                                if (playUrl.isNullOrBlank()) {
                                    ToastCenter.showError(result.exceptionOrNull()?.message ?: "切换失败")
                                    return@withContext
                                }

                                val newSource = rebuildStorageVideoSource(storageSource, playUrl)
                                pendingSeekPositionMs = positionMs
                                applyPlaySource(newSource)
                            }
                        } finally {
                            withContext(NonCancellable + Dispatchers.Main) {
                                hideLoading()
                            }
                        }
                    }
            }
        }
    }

    private fun cancelPlaybackAddonJob(resetAttempts: Boolean) {
        playbackAddonJob?.cancel()
        playbackAddonJob = null
        if (resetAttempts) {
            playbackRecoveryAttempts = 0
        }
        hideLoading()
    }

    private fun applyPlaySource(newSource: BaseVideoSource?) {
        LogFacade.i(
            LogModule.PLAYER,
            TAG_SOURCE,
            "apply start title=${newSource?.getVideoTitle()} type=${newSource?.getMediaType()} urlHash=${newSource?.getVideoUrl()?.hashCode()}",
        )
        val previousSource = videoSource
        if (previousSource != null &&
            (
                newSource == null ||
                    previousSource.getStorageId() != newSource.getStorageId() ||
                    previousSource.getUniqueKey() != newSource.getUniqueKey()
            )
        ) {
            releasePlaybackAddonIfNeeded(previousSource)
        }

        cancelPlaybackAddonJob(resetAttempts = true)
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

        Media3Diagnostics.clearLastHttpOpen()
        dispatchPlaybackSourceChangedEvent(source)

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
            val isBilibiliLive = StorageDanmuMatcher.isBilibiliLive(source)
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

    private fun dispatchPlaybackErrorEvent(
        source: BaseVideoSource,
        throwable: Throwable?,
        scene: String
    ) {
        val addon = source.getPlaybackAddon() ?: return
        val identity = buildPlaybackIdentity(source)
        val diagnostics = buildPlaybackDiagnostics(source, throwable)
        runCatching {
            addon.onEvent(
                PlaybackEvent.PlaybackError(
                    identity = identity,
                    throwable = throwable,
                    scene = scene,
                    diagnostics = diagnostics,
                ),
            )
        }
    }

    private fun buildPlaybackIdentity(source: BaseVideoSource): PlaybackIdentity =
        PlaybackIdentity(
            storageId = source.getStorageId(),
            uniqueKey = source.getUniqueKey(),
            mediaType = source.getMediaType(),
            storagePath = source.getStoragePath(),
            videoTitle = source.getVideoTitle(),
            videoUrl = source.getVideoUrl(),
        )

    private fun releasePlaybackAddonIfNeeded(source: BaseVideoSource) {
        val addon = source.getPlaybackAddon() as? PlaybackReleasableAddon ?: return
        runCatching { addon.onRelease() }
    }

    private fun dispatchPlaybackSourceChangedEvent(source: BaseVideoSource) {
        val addon = source.getPlaybackAddon() ?: return
        val identity = buildPlaybackIdentity(source)
        runCatching {
            addon.onEvent(
                PlaybackEvent.SourceChanged(
                    identity = identity,
                    httpHeader = source.getHttpHeader(),
                ),
            )
        }
    }

    private fun buildPlaybackDiagnostics(
        source: BaseVideoSource,
        throwable: Throwable?
    ): Map<String, String> {
        val diagnostics = linkedMapOf<String, String>()
        diagnostics["playerType"] = PlayerInitializer.playerType.name
        diagnostics["playState"] = danDanPlayer.getPlayState().name
        diagnostics["positionMs"] = danDanPlayer.getCurrentPosition().toString()
        diagnostics["durationMs"] = danDanPlayer.getDuration().toString()
        diagnostics["bufferedPct"] = danDanPlayer.getBufferedPercentage().toString()
        diagnostics["isLiveFlag"] = danDanPlayer.isLive().toString()
        diagnostics["videoTitle"] = source.getVideoTitle()

        latestMedia3Session?.let {
            diagnostics["media3.sessionId"] = it.sessionId
            diagnostics["media3.mediaId"] = it.mediaId
            diagnostics["media3.playerEngine"] = it.playerEngine.name
            diagnostics["media3.sourceType"] = it.sourceType.name
            diagnostics["media3.toggleCohort"] = it.toggleCohort?.name ?: "UNKNOWN"
        }

        val invalidResponseCode = findInvalidResponseCodeException(throwable)
        val lastHttpOpen = Media3Diagnostics.snapshotLastHttpOpen()
        val failingUrl =
            lastHttpOpen?.url?.takeIf { it.isNotBlank() }
                ?: invalidResponseCode?.dataSpec?.uri?.toString()
        if (!failingUrl.isNullOrBlank()) {
            diagnostics["failingUrl"] = failingUrl
        }

        lastHttpOpen?.let {
            diagnostics["lastHttpUrl"] = it.url.orEmpty()
            diagnostics["lastHttpContentType"] = it.contentType.orEmpty()
            diagnostics["lastHttpTimestampMs"] = it.timestampMs.toString()
            it.code?.let { code -> diagnostics["httpResponseCode"] = code.toString() }
        }

        val playbackException = findPlaybackException(throwable)
        playbackException?.let {
            diagnostics["media3.errorCode"] = it.errorCode.toString()
            diagnostics["media3.errorCodeName"] = it.errorCodeName
        }

        invalidResponseCode?.let { invalid ->
            diagnostics["httpResponseCode"] = invalid.responseCode.toString()
        }

        val isDecoderError = playbackException?.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
        diagnostics["isDecoderError"] = isDecoderError.toString()

        return diagnostics
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

    private fun tryRecoverPlayback(
        source: BaseVideoSource,
        playbackError: Throwable?
    ): Boolean {
        val storageSource = source as? StorageVideoSource ?: return false
        val addon = storageSource.getPlaybackAddon() as? PlaybackUrlRecoverableAddon ?: return false

        if (playbackAddonJob?.isActive == true) {
            return true
        }
        if (playbackRecoveryAttempts >= 3) {
            return false
        }
        playbackRecoveryAttempts += 1

        val positionMs = danDanPlayer.getCurrentPosition()
        val request =
            PlaybackRecoveryRequest(
                identity = buildPlaybackIdentity(source),
                positionMs = positionMs,
                playbackError = playbackError,
                diagnostics = buildPlaybackDiagnostics(source, playbackError),
            )

        showLoading()
        danDanPlayer.pause()
        playbackAddonJob =
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val result = addon.recover(request)
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        val playUrl = result.getOrNull()
                        if (playUrl.isNullOrBlank()) {
                            showPlayErrorDialog()
                            return@withContext
                        }

                        val newSource = rebuildStorageVideoSource(storageSource, playUrl)
                        pendingSeekPositionMs = positionMs
                        playbackRecoveryAttempts = 0
                        videoController.showMessage("已自动恢复播放")
                        applyPlaySource(newSource)
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        hideLoading()
                    }
                }
            }

        return true
    }

    private fun rebuildStorageVideoSource(
        source: StorageVideoSource,
        playUrl: String
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
                        StorageVideoSourceFactory
                            .create(storageSource.getStorageFile(), fallbackProfile)
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
        releasePlaybackAddonIfNeeded(source)
        cancelPlaybackAddonJob(resetAttempts = false)
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
