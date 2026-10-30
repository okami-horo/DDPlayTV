package com.xyoye.player.controller

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.lifecycle.LiveData
import androidx.media3.common.util.UnstableApi
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.entity.DanmuBlockEntity
import com.xyoye.data_component.enums.PlayState
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player.controller.action.PlayerAction
import com.xyoye.player.controller.base.TvVideoController
import com.xyoye.player.controller.danmu.DanmuController
import com.xyoye.player.controller.setting.DanmuPanelState
import com.xyoye.player.controller.setting.SettingController
import com.xyoye.player.controller.subtitle.SubtitleController
import com.xyoye.player.controller.video.LoadingView
import com.xyoye.player.controller.video.PlayerBottomView
import com.xyoye.player.controller.video.PlayerControlView
import com.xyoye.player.controller.video.PlayerGestureView
import com.xyoye.player.controller.video.PlayerTopView
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.utils.MessageTime
import com.xyoye.subtitle.MixedSubtitle

/**
 * Created by xyoye on 2020/11/3.
 */

@UnstableApi
class VideoController(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TvVideoController(context, attrs, defStyleAttr) {
    // 弹幕视图控制器
    private val mDanmuController = DanmuController(context)

    // 字幕视图控制器
    private val mSubtitleController = SubtitleController(context)

    // 设置视图控制器
    private val mSettingController =
        SettingController(
            context,
            { addControlComponent(it) },
            onSettingHidden = { onSettingHidden() },
            onSettingShown = {
                settingCallerId = findFocus()?.id ?: View.NO_ID
                stopFadeOut()
            }
        )

    private val playerTopView = PlayerTopView(context)
    private val playerBotView = PlayerBottomView(context)
    private val gestureView = PlayerGestureView(context)
    private val loadingView = LoadingView(context)

    private val playerControlView = PlayerControlView(context)

    private var settingCallerId = View.NO_ID
    private var lastVideoSpeed: Float? = null

    private var switchVideoSourceBlock: ((Int) -> Unit)? = null
    private var autoPlayNextBlock: ((Int) -> Unit)? = null
    private var trackAddedBlock: ((VideoTrackBean) -> Unit)? = null
    private var playbackProgressBlock: (() -> Unit)? = null

    init {
        addControlComponent(mDanmuController.getView())
        addControlComponent(*mSubtitleController.getViews())
        // 遥控器快进/快退预览。
        addControlComponent(gestureView)
        addControlComponent(playerTopView)
        addControlComponent(playerBotView)
        addControlComponent(loadingView)
        addControlComponent(playerControlView)

        actionHandler = { action -> handleAction(action) }
        playerBotView.setActionHandler { action -> dispatchAction(action) }
    }

    override fun getDanmuController() = mDanmuController

    override fun getSubtitleController() = mSubtitleController

    override fun getSettingController() = mSettingController

    override fun showMessage(
        text: String,
        time: MessageTime
    ) {
        playerControlView.showMessage(text, time)
    }

    override fun setTrackAdded(track: VideoTrackBean) {
        trackAddedBlock?.invoke(track)
    }

    override fun onPlayStateChanged(playState: PlayState) {
        super.onPlayStateChanged(playState)
        if (playState == PlayState.STATE_PLAYING) {
            considerSetVideoSpeed()
        } else if (playState == PlayState.STATE_COMPLETED) {
            if (PlayerInitializer.Player.isAutoPlayNext) {
                val videoSource = mControlWrapper.getVideoSource()
                if (videoSource.hasNextSource()) {
                    val nextIndex = videoSource.getGroupIndex() + 1
                    autoPlayNextBlock?.invoke(nextIndex) ?: switchVideoSourceBlock?.invoke(nextIndex)
                    return
                }
            }
            mPlayCompletionBlock?.invoke()
        }
    }

    override fun onProgressChanged(
        duration: Long,
        position: Long
    ) {
        super.onProgressChanged(duration, position)
        playbackProgressBlock?.invoke()
    }

    override fun onBackPressed(): Boolean {
        if (isLocked()) {
            showController()
            return true
        }
        if (mControlWrapper.isSettingViewShowing()) {
            mControlWrapper.hideSettingView()
            return true
        }
        if (isControllerShowing()) {
            mControlWrapper.hideController()
            return true
        }
        return super.onBackPressed()
    }

    override fun release() {
        clearSourceScopedState()
    }

    override fun destroy() {
        cancelPendingSeek()
    }

    private fun onSettingHidden() {
        val caller = findViewById<View>(settingCallerId)
        if (!isInTouchMode && caller != null && caller !== this && caller.isShown && caller.isFocusable) {
            caller.requestFocus()
            startFadeOut()
        } else {
            hideController()
        }
        settingCallerId = View.NO_ID
    }

    /**
     * 设置视频标题
     */
    fun setVideoTitle(title: String?) {
        playerTopView.setVideoTitle(title)
    }

    /**
     * 添加扩展轨道
     */
    fun addExtendTrack(track: VideoTrackBean): Boolean = mControlWrapper.addTrack(track)

    /**
     * 设置上次播放速度
     */
    fun setLastPlaySpeed(speed: Float) {
        lastVideoSpeed = speed
    }

    internal fun seekPlaybackTo(positionMs: Long) = mControlWrapper.seekTo(positionMs)

    internal fun changePlaybackSpeed(speed: Float) = mControlWrapper.setSpeed(speed)

    /*
     * 设置电量数据
     *
     * fun setBatteryHelper(helper: BatteryHelper) {
     *     playerTopView.setBatteryHelper(helper)
     * }
     */

    /**
     * 播放错误回调
     */
    fun observerPlayError(block: () -> Unit) {
        mPlayErrorBlock = block
    }

    /**
     * 退出播放回调
     */
    fun observerExitPlayer(block: () -> Unit) {
        mPlayCompletionBlock = block
        playerTopView.setExitPlayerObserver(block)
    }

    /**
     * 监听轨道添加完成
     */
    fun observerTrackAdded(block: (VideoTrackBean) -> Unit) {
        trackAddedBlock = block
    }

    /**
     * 切换视频资源回调
     */
    fun setSwitchVideoSourceBlock(block: (Int) -> Unit) {
        this.switchVideoSourceBlock = block
        playerBotView.setSwitchVideoSourceBlock(block)
        mSettingController.setSwitchVideoSourceBlock(block)
    }

    fun observerAutoPlayNext(block: (Int) -> Unit) {
        autoPlayNextBlock = block
    }

    fun observerPlaybackProgress(block: () -> Unit) {
        playbackProgressBlock = block
    }

    fun clearSourceScopedState() {
        cancelPendingSeek()
        mDanmuController.danmuRelease()
        mSubtitleController.clearExternalTrack()
        mSettingController.hideSettingView()
        playerControlView.clearMessage()
    }

    /**
     * 弹幕屏蔽回调
     */
    fun observerDanmuBlock(
        cloudBlock: LiveData<MutableList<DanmuBlockEntity>>? = null,
        add: ((keyword: String, isRegex: Boolean) -> Unit),
        remove: ((id: Int) -> Unit),
        queryAll: () -> LiveData<MutableList<DanmuBlockEntity>>
    ) {
        mDanmuController.setCloudBlockLiveData(cloudBlock)
        mSettingController.setDatabaseBlock(add, remove, queryAll)
    }

    /**
     * 弹幕搜索
     */
    fun observerDanmuSearch(
        search: (String) -> Unit,
        download: (DanmuEpisodeData) -> Unit,
        searchResult: () -> LiveData<DanmuPanelState>
    ) {
        mSettingController.setDanmuSearch(search, download, searchResult)
    }

    fun onDanmuApplied() = mSettingController.onDanmuApplied()

    /**
     * 更新字幕内容
     *
     * 由播放器调用
     */
    fun onSubtitleTextOutput(subtitle: MixedSubtitle) {
        mSubtitleController.onSubtitleTextOutput(subtitle)
    }

    private fun considerSetVideoSpeed() {
        lastVideoSpeed?.let {
            mControlWrapper.setSpeed(it)
        }
        lastVideoSpeed = null
    }

    private fun handleAction(action: PlayerAction) {
        when (action) {
            PlayerAction.TogglePlay -> mControlWrapper.togglePlay()
            is PlayerAction.SeekBy -> changePosition(action.offsetMs)
            PlayerAction.ShowController -> showController(true)
            PlayerAction.OpenPlayerSettings -> mControlWrapper.showSettingView(SettingViewType.PLAYER_SETTING)
            PlayerAction.OpenEpisodePanel,
            PlayerAction.OpenSourceList -> {
                mControlWrapper.showSettingView(SettingViewType.SWITCH_VIDEO_SOURCE)
            }
            PlayerAction.NextSource -> {
                val videoSource = mControlWrapper.getVideoSource()
                if (videoSource.hasNextSource()) {
                    switchVideoSourceBlock?.invoke(videoSource.getGroupIndex() + 1)
                } else {
                    showController(true)
                }
            }
            PlayerAction.PreviousSource -> {
                val videoSource = mControlWrapper.getVideoSource()
                if (videoSource.hasPreviousSource()) {
                    switchVideoSourceBlock?.invoke(videoSource.getGroupIndex() - 1)
                } else {
                    showController(true)
                }
            }
            PlayerAction.ToggleDanmu -> mControlWrapper.toggleDanmuVisible()
            PlayerAction.ToggleSubtitle -> mControlWrapper.toggleSubtitleVisible()
        }
    }
}
