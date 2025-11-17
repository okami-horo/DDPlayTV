package com.xyoye.player_component.ui.activities.player

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.IBinder
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.gyf.immersionbar.BarHide
import com.gyf.immersionbar.ImmersionBar
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.bridge.PlayTaskBridge
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.common_component.enums.RendererPreferenceSource
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.receiver.HeadsetBroadcastReceiver
import com.xyoye.common_component.receiver.PlayerReceiverListener
import com.xyoye.common_component.receiver.ScreenBroadcastReceiver
import com.xyoye.common_component.source.VideoSourceManager
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.utils.DDLog
import com.xyoye.common_component.utils.screencast.ScreencastHandler
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.DanmakuLanguage
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PixelFormat
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.data_component.enums.SubtitleFallbackReason
import com.xyoye.data_component.enums.VLCAudioOutput
import com.xyoye.data_component.enums.VLCHWDecode
import com.xyoye.data_component.enums.VLCPixelFormat
import com.xyoye.common_component.media3.Media3SessionClient
import com.xyoye.data_component.entity.media3.Media3BackgroundMode
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player.controller.VideoController
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player_component.BR
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ActivityPlayerBinding
import com.xyoye.player_component.widgets.popup.PlayerPopupManager
import com.xyoye.player.subtitle.backend.SubtitleFallbackDispatcher
import com.xyoye.player.subtitle.debug.PlaybackSessionStatusProvider
import com.xyoye.player.subtitle.ui.SubtitleFallbackDialog
import com.xyoye.player.subtitle.ui.SubtitleFallbackDialog.SubtitleFallbackAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Route(path = RouteTable.Player.PlayerCenter)
class PlayerActivity : BaseActivity<PlayerViewModel, ActivityPlayerBinding>(),
    PlayerReceiverListener, ScreencastHandler, SubtitleFallbackDispatcher {

    companion object {
        private const val MEDIA3_SESSION_SERVICE =
            "com.xyoye.dandanplay.app.service.Media3SessionService"
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

    //悬浮窗
    private val popupManager: PlayerPopupManager by lazy {
        PlayerPopupManager(this)
    }

    //锁屏广播
    private lateinit var screenLockReceiver: ScreenBroadcastReceiver

    //耳机广播
    private lateinit var headsetReceiver: HeadsetBroadcastReceiver

    private var videoSource: BaseVideoSource? = null

    //电量管理
    /*
    private var batteryHelper = BatteryHelper()
    */
    private var media3SessionClient: Media3SessionClient? = null
    private var media3ServiceBound = false
    private var media3BackgroundModes: Set<Media3BackgroundMode> = emptySet()
    private var media3BackgroundJob: Job? = null
    private var latestMedia3Session: PlaybackSession? = null
    private var latestMedia3Capability: PlayerCapabilityContract? = null
    private var subtitleFallbackDialog: SubtitleFallbackDialog? = null

    private val media3ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val client = service as? Media3SessionClient ?: return
            media3SessionClient = client
            media3ServiceBound = true
            pushMedia3SessionToService()
            media3BackgroundJob = lifecycleScope.launch {
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
            PlayerViewModel::class.java
        )

    override fun getLayoutId() = R.layout.activity_player

    override fun initStatusBar() {
        ImmersionBar.with(this)
            .fullScreen(true)
            .hideBar(BarHide.FLAG_HIDE_BAR)
            .init()
    }

    override fun initView() {
        ARouter.getInstance().inject(this)

        DDLog.i(
            "PLAYER-Activity",
            "initView start intent=${intent?.action.orEmpty()} source=${VideoSourceManager.getInstance().getSource()?.javaClass?.simpleName ?: "null"}"
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

        DDLog.i("PLAYER-Activity", "initView finished")
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
            DDLog.w("PLAYER-Activity", "bind Media3SessionService failed: ${e.message}")
        }
    }

    override fun onStop() {
        if (media3ServiceBound) {
            unbindService(media3ServiceConnection)
            media3ServiceBound = false
        }
        media3BackgroundJob?.cancel()
        media3BackgroundJob = null
        media3BackgroundModes = emptySet()
        super.onStop()
    }

    override fun onPause() {
        val popupNotShowing = popupManager.isShowing().not()
        val backgroundAllowed = PlayerConfig.isBackgroundPlay() && media3SupportsBackgroundPlayback()
        val backgroundPlayDisable = backgroundAllowed.not()
        DDLog.i(
            "PLAYER-Activity",
            "onPause popupNotShowing=$popupNotShowing backgroundAllowed=$backgroundAllowed"
        )
        if (popupNotShowing && backgroundPlayDisable) {
            danDanPlayer.pause()
        }
        danDanPlayer.recordPlayInfo()
        super.onPause()
    }

    override fun onDestroy() {
        DDLog.i("PLAYER-Activity", "onDestroy")
        beforePlayExit()
        unregisterReceiver()
        subtitleFallbackDialog?.dismiss()
        subtitleFallbackDialog = null
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return danDanPlayer.onKeyDown(keyCode, event) or super.onKeyDown(keyCode, event)
    }

    override fun onScreenLocked() {

    }

    override fun onHeadsetRemoved() {
        danDanPlayer.pause()
    }

    override fun onSubtitleBackendFallback(reason: SubtitleFallbackReason, error: Throwable?) {
        runOnUiThread {
            if (subtitleFallbackDialog == null) {
                subtitleFallbackDialog = SubtitleFallbackDialog(this) { action ->
                    when (action) {
                        SubtitleFallbackAction.SWITCH_TO_LEGACY -> handleSubtitleFallbackSwitch(reason)
                        SubtitleFallbackAction.CONTINUE_CURRENT -> {
                            DDLog.w(
                                "PLAYER-Subtitle",
                                "User chose to continue with libass despite $reason"
                            )
                        }
                    }
                }.also { dialog ->
                    dialog.setOnDismissListener { subtitleFallbackDialog = null }
                }
            }
            subtitleFallbackDialog?.show()
        }
    }

    private fun handleSubtitleFallbackSwitch(reason: SubtitleFallbackReason) {
        if (PlayerInitializer.Subtitle.backend == SubtitleRendererBackend.LEGACY_CANVAS) {
            return
        }
        SubtitlePreferenceUpdater.persistBackend(
            SubtitleRendererBackend.LEGACY_CANVAS,
            RendererPreferenceSource.LOCAL_SETTINGS
        )
        PlayerInitializer.Subtitle.backend = SubtitleRendererBackend.LEGACY_CANVAS
        PlaybackSessionStatusProvider.updateBackend(SubtitleRendererBackend.LEGACY_CANVAS)
        PlaybackSessionStatusProvider.markFallback(reason, null)
        danDanPlayer.switchSubtitleBackend(SubtitleRendererBackend.LEGACY_CANVAS)
        ToastCenter.showOriginalToast(getString(R.string.subtitle_backend_fallback_result))
        DDLog.i("PLAYER-Subtitle", "Fallback applied due to $reason")
    }

    override fun playScreencast(videoSource: BaseVideoSource) {
        DDLog.i(
            "PLAYER-Cast",
            "receive screencast title=${videoSource.getVideoTitle()} type=${videoSource.getMediaType()}"
        )
        lifecycleScope.launch(Dispatchers.Main) {
            applyPlaySource(videoSource)
        }
    }

    private fun checkPlayParams(source: BaseVideoSource?): Boolean {
        if (source == null || source.getVideoUrl().isEmpty()) {
            DDLog.w("PLAYER-Source", "invalid source url=${source?.getVideoUrl()} type=${source?.getMediaType()}")
            CommonDialog.Builder(this).run {
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
                DDLog.i("PLAYER-Danmaku", "skip load result mismatch url")
                return@observe
            }

            DDLog.i(
                "PLAYER-Danmaku",
                "match success cid=${matchDanmu?.episodeId} title=${curVideoSource.getVideoTitle()}"
            )
            videoController.showMessage("匹配弹幕成功")
            videoController.addExtendTrack(VideoTrackBean.danmu(matchDanmu))
        }

        danmuViewModel.downloadDanmuLiveData.observe(this) { searchDanmu ->
            if (searchDanmu == null) {
                DDLog.w("PLAYER-Danmaku", "download failed")
                videoController.showMessage("下载弹幕失败")
                return@observe
            }

            DDLog.i("PLAYER-Danmaku", "download success id=${searchDanmu.episodeId}")
            videoController.showMessage("下载弹幕成功")
            videoController.addExtendTrack(VideoTrackBean.danmu(searchDanmu))
        }
    }

    private fun initPlayer() {
        videoController.apply {
            /*
            setBatteryHelper(batteryHelper)
            */

            //播放错误
            observerPlayError {
                DDLog.e(
                    "PLAYER-Playback",
                    "play error title=${danDanPlayer.getVideoSource().getVideoTitle()} position=${danDanPlayer.getCurrentPosition()}"
                )
                showPlayErrorDialog()
            }
            //退出播放
            observerExitPlayer {
                if (popupManager.isShowing()) {
                    DDLog.i("PLAYER-Playback", "exit from popup mode")
                    danDanPlayer.recordPlayInfo()
                }
                popupManager.dismiss()
                DDLog.i("PLAYER-Playback", "exit player title=${danDanPlayer.getVideoSource().getVideoTitle()}")
                finish()
            }
            //弹幕屏蔽
            observerDanmuBlock(
                cloudBlock = viewModel.cloudDanmuBlockLiveData,
                add = { keyword, isRegex -> viewModel.addDanmuBlock(keyword, isRegex) },
                remove = { id -> viewModel.removeDanmuBlock(id) },
                queryAll = { viewModel.localDanmuBlockLiveData }
            )
            //弹幕搜索
            observerDanmuSearch(
                search = { danmuViewModel.searchDanmu(it) },
                download = { danmuViewModel.downloadDanmu(it) },
                searchResult = { danmuViewModel.danmuSearchLiveData }
            )
            //进入悬浮窗模式
            observerEnterPopupMode {
                enterPopupMode()
                enterTaskBackground()
            }
            //退出悬浮窗模式
            observerExitPopupMode {
                exitPopupMode()
                exitTaskBackground()
            }
            // 轨道添加完成
            observerTrackAdded {
                val source = videoSource ?: return@observerTrackAdded
                viewModel.storeTrackAdded(source, it)
            }
        }
    }

    private fun applyPlaySource(newSource: BaseVideoSource?) {
        DDLog.i(
            "PLAYER-Source",
            "apply start title=${newSource?.getVideoTitle()} type=${newSource?.getMediaType()} urlHash=${newSource?.getVideoUrl()?.hashCode()}"
        )
        danDanPlayer.recordPlayInfo()
        danDanPlayer.pause()
        danDanPlayer.release()
        videoController.release()

        videoSource = newSource
        if (checkPlayParams(videoSource).not()) {
            DDLog.w("PLAYER-Source", "apply abort invalid source")
            return
        }
        VideoSourceManager.getInstance().setSource(videoSource!!)

        val overrideParams = VideoSourceManager.getInstance().consumeMedia3LaunchParams()
        val launchParams = viewModel.buildMedia3LaunchParams(videoSource!!, overrideParams)
        viewModel.prepareMedia3Session(launchParams)

        updatePlayer(videoSource!!)

        afterInitPlayer()
        DDLog.i("PLAYER-Source", "apply finished title=${videoSource?.getVideoTitle()}")
    }

    private fun updatePlayer(source: BaseVideoSource) {
        videoController.apply {
            setVideoTitle(source.getVideoTitle())
            setLastPosition(source.getCurrentPosition())
            setLastPlaySpeed(PlayerConfig.getNewVideoSpeed())
        }

        danDanPlayer.apply {
            setVideoSource(source)
            start()
            DDLog.i(
                "PLAYER-Playback",
                "start title=${source.getVideoTitle()} type=${source.getMediaType()} position=${source.getCurrentPosition()} speed=${PlayerConfig.getNewVideoSpeed()}"
            )
        }
        /*
        //发送弹幕
        videoController.observerSendDanmu {
            DDLog.i("PLAYER-Danmaku", "send request text=${it.text}")
            viewModel.sendDanmu(source.getDanmu(), it)
        }
        */

        videoController.setSwitchVideoSourceBlock {
            DDLog.i("PLAYER-Source", "switch request index=$it title=${source.getVideoTitle()}")
            switchVideoSource(it)
        }
    }

    private fun afterInitPlayer() {
        val source = videoSource ?: return

        // 设置当前视频的父目录（若为本地可访问路径），用于字幕/字体同目录检索
        File(source.getVideoUrl()).parentFile?.let { parent ->
            if (parent.exists()) {
                PlayerInitializer.selectSourceDirectory = parent.absolutePath
                DDLog.i(
                    "PLAYER-Source",
                    "select directory set path=${parent.absolutePath} type=${source.getMediaType()}"
                )
            }
        }

        // 视频已绑定弹幕，直接加载，否则尝试匹配弹幕
        val historyDanmu = source.getDanmu()
        if (historyDanmu != null) {
            DDLog.i("PLAYER-Danmaku", "load history cid=${historyDanmu.episodeId}")
            videoController.addExtendTrack(VideoTrackBean.danmu(historyDanmu))
        } else if (
            DanmuConfig.isAutoMatchDanmu()
            && source.getMediaType() != MediaType.FTP_SERVER
            && popupManager.isShowing().not()
        ) {
            DDLog.i("PLAYER-Danmaku", "auto match start title=${source.getVideoTitle()}")
            danmuViewModel.matchDanmu(source)
        }

        // 视频已绑定字幕，直接加载
        val historySubtitle = source.getSubtitlePath()
        if (historySubtitle != null) {
            DDLog.i("PLAYER-Subtitle", "load history path=${historySubtitle}")
            videoController.addExtendTrack(VideoTrackBean.subtitle(historySubtitle))
        }

        // 视频已绑定音频，直接加载
        val historyAudio = source.getAudioPath()
        if (historyAudio != null) {
            DDLog.i("PLAYER-Audio", "load history path=${historyAudio}")
            videoController.addExtendTrack(VideoTrackBean.audio(historyAudio))
        }
    }

    private fun registerReceiver() {
        DDLog.i("PLAYER-Activity", "register receivers")
        screenLockReceiver = ScreenBroadcastReceiver(this)
        headsetReceiver = HeadsetBroadcastReceiver(this)
        registerReceiver(screenLockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registerReceiver(headsetReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        /*
        batteryHelper.registerReceiver(this)
        */
    }

    private fun unregisterReceiver() {
        if (this::screenLockReceiver.isInitialized) {
            unregisterReceiver(screenLockReceiver)
            DDLog.i("PLAYER-Activity", "unregister screen receiver")
        }
        if (this::headsetReceiver.isInitialized) {
            unregisterReceiver(headsetReceiver)
            DDLog.i("PLAYER-Activity", "unregister headset receiver")
        }
        /*
        batteryHelper.unregisterReceiver(this)
        DDLog.i("PLAYER-Activity", "unregister battery receiver")
        */
    }

    private fun initPlayerConfig() {
        //播放器类型
        PlayerInitializer.playerType = PlayerType.valueOf(PlayerConfig.getUsePlayerType())
        DDLog.i("PLAYER-Config", "playerType=${PlayerInitializer.playerType}")
        //IJKPlayer像素格式
        PlayerInitializer.Player.pixelFormat =
            PixelFormat.valueOf(PlayerConfig.getUsePixelFormat())
        //IJKPlayer硬解码
        PlayerInitializer.Player.isMediaCodeCEnabled = PlayerConfig.isUseMediaCodeC()
        //IJKPlayer H265硬解码
        PlayerInitializer.Player.isMediaCodeCH265Enabled = PlayerConfig.isUseMediaCodeCH265()
        //IJKPlayer OpenSlEs
        PlayerInitializer.Player.isOpenSLESEnabled = PlayerConfig.isUseOpenSlEs()
        //是否使用SurfaceView
        PlayerInitializer.surfaceType =
            if (PlayerConfig.isUseSurfaceView()) SurfaceType.VIEW_SURFACE else SurfaceType.VIEW_TEXTURE
        //视频速度
        PlayerInitializer.Player.videoSpeed = PlayerConfig.getNewVideoSpeed()
        // 长按视频速度
        PlayerInitializer.Player.pressVideoSpeed = PlayerConfig.getPressVideoSpeed()
        //自动播放下一集
        PlayerInitializer.Player.isAutoPlayNext = PlayerConfig.isAutoPlayNext()

        //VLCPlayer像素格式
        PlayerInitializer.Player.vlcPixelFormat =
            VLCPixelFormat.valueOf(PlayerConfig.getUseVLCPixelFormat())
        PlayerInitializer.Player.vlcHWDecode =
            VLCHWDecode.valueOf(PlayerConfig.getUseVLCHWDecoder())
        PlayerInitializer.Player.vlcAudioOutput =
            VLCAudioOutput.valueOf(PlayerConfig.getUseVLCAudioOutput())
        DDLog.i("PLAYER-Config", "vlc h265=${PlayerInitializer.Player.isMediaCodeCH265Enabled} hw=${PlayerInitializer.Player.vlcHWDecode}")

        //弹幕配置
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
        DDLog.i(
            "PLAYER-Config",
            "danmu size=${PlayerInitializer.Danmu.size} speed=${PlayerInitializer.Danmu.speed} language=${PlayerInitializer.Danmu.language}"
        )

        //字幕配置
        PlayerInitializer.Subtitle.textSize = SubtitleConfig.getTextSize()
        PlayerInitializer.Subtitle.strokeWidth = SubtitleConfig.getStrokeWidth()
        PlayerInitializer.Subtitle.textColor = SubtitleConfig.getTextColor()
        PlayerInitializer.Subtitle.strokeColor = SubtitleConfig.getStrokeColor()
        PlayerInitializer.Subtitle.alpha = SubtitleConfig.getAlpha()
        PlayerInitializer.Subtitle.verticalOffset = SubtitleConfig.getVerticalOffset()
        val backend = SubtitleRendererBackend.fromName(SubtitleConfig.getSubtitleRendererBackend())
        PlayerInitializer.Subtitle.backend = if (PlayerInitializer.playerType == PlayerType.TYPE_EXO_PLAYER) {
            backend
        } else {
            DDLog.w(
                "PLAYER-Config",
                "libass backend requires ExoPlayer, fallback to legacy for playerType=${PlayerInitializer.playerType}"
            )
            SubtitleRendererBackend.LEGACY_CANVAS
        }
        DDLog.i(
            "PLAYER-Config",
            "subtitle size=${PlayerInitializer.Subtitle.textSize} stroke=${PlayerInitializer.Subtitle.strokeWidth} backend=${PlayerInitializer.Subtitle.backend}"
        )
    }

    private fun showPlayErrorDialog() {
        val source = videoSource
        val isTorrentSource = source?.getMediaType() == MediaType.MAGNET_LINK

        val tips = if (source is StorageVideoSource && isTorrentSource) {
            val taskLog = PlayTaskBridge.getTaskLog(source.getPlayTaskId())
            "播放失败，资源已失效或暂时无法访问，请尝试切换资源$taskLog"
        } else {
            "播放失败，请尝试更改播放器设置，或者切换其它播放内核"
        }

        val builder = AlertDialog.Builder(this@PlayerActivity)
            .setTitle("错误")
            .setCancelable(false)
            .setMessage(tips)
            .setNegativeButton("退出播放") { dialog, _ ->
                dialog.dismiss()
                this@PlayerActivity.finish()
            }

        if (isTorrentSource) {
            builder.setPositiveButton("播放器设置") { dialog, _ ->
                dialog.dismiss()
                ARouter.getInstance()
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

    private fun media3SupportsBackgroundPlayback(): Boolean {
        return media3BackgroundModes.contains(Media3BackgroundMode.NOTIFICATION)
    }

    private fun media3SupportsPip(): Boolean {
        return media3BackgroundModes.contains(Media3BackgroundMode.PIP)
    }

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
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
    }
}
