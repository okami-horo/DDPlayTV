package com.xyoye.player.controller

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import androidx.lifecycle.LiveData
import com.xyoye.common_component.utils.formatDuration
import com.xyoye.data_component.bean.SendDanmuBean
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.entity.DanmuBlockEntity
import com.xyoye.data_component.enums.PlayState
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player.controller.action.PlayerAction
import com.xyoye.player.controller.base.GestureVideoController
import com.xyoye.player.controller.danmu.DanmuController
import com.xyoye.player.controller.setting.SettingController
import com.xyoye.player.controller.subtitle.SubtitleController
import com.xyoye.player.controller.video.LoadingView
import com.xyoye.player.controller.video.PlayerBottomView
import com.xyoye.player.controller.video.PlayerControlView
import com.xyoye.player.controller.video.PlayerGestureView
import com.xyoye.player.controller.video.PlayerPopupControlView
import com.xyoye.player.controller.video.PlayerTopView
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.utils.MessageTime
import com.xyoye.subtitle.MixedSubtitle
import androidx.media3.common.util.UnstableApi

/**
 * Created by xyoye on 2020/11/3.
 */

@UnstableApi
class VideoController(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : GestureVideoController(context, attrs, defStyleAttr) {
    private val isTvUiMode: Boolean = context.isTelevisionUiMode()
    //弹幕视图控制器
    private val mDanmuController = DanmuController(context)

    //字幕视图控制器
    private val mSubtitleController = SubtitleController(context)

    //设置视图控制器
    private val mSettingController = SettingController(
        context,
        { addControlComponent(it) },
        onSettingHidden = { onSettingHidden() }
    )

    private val playerTopView = PlayerTopView(context)
    private val playerBotView = PlayerBottomView(context)
    private val gestureView = PlayerGestureView(context)
    private val loadingView = LoadingView(context)

    private val playerControlView = PlayerControlView(context)
    private val playerPopupControlView = PlayerPopupControlView(context)

    private var lastPlayPosition = 0L
    private var lastVideoSpeed: Float? = null

    private var switchVideoSourceBlock: ((Int) -> Unit)? = null
    private var trackAddedBlock: ((VideoTrackBean) -> Unit)? = null

    init {
        addControlComponent(mDanmuController.getView())
        addControlComponent(*mSubtitleController.getViews())
        // TV 模式也需要手势视图用于展示快进/快退提示（触摸事件会在 TV 上被禁用）
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

    override fun showMessage(text: String, time: MessageTime) {
        playerControlView.showMessage(text, time)
    }

    override fun setTrackAdded(track: VideoTrackBean) {
        trackAddedBlock?.invoke(track)
    }

    override fun onPopupModeChanged(isPopup: Boolean) {
        super.onPopupModeChanged(isPopup)
        mSettingController.setPopupMode(isPopup)

        if (isPopup) {
            addControlComponent(playerPopupControlView)

            if (!isTvUiMode) {
                removeControlComponent(gestureView)
            }
            removeControlComponent(playerTopView)
            removeControlComponent(playerBotView)
            removeControlComponent(playerControlView)
        } else {
            removeControlComponent(playerPopupControlView)

            if (!isTvUiMode) {
                addControlComponent(gestureView)
            }
            addControlComponent(playerTopView)
            addControlComponent(playerBotView)
            addControlComponent(playerControlView)
        }
    }

    override fun onPlayStateChanged(playState: PlayState) {
        super.onPlayStateChanged(playState)
        if (playState == PlayState.STATE_PLAYING) {
            considerSeekToLastPlay()
            considerSetVideoSpeed()
        } else if (playState == PlayState.STATE_COMPLETED) {
            if (PlayerInitializer.Player.isAutoPlayNext) {
                val videoSource = mControlWrapper.getVideoSource()
                if (videoSource.hasNextSource()) {
                    switchVideoSourceBlock?.invoke(videoSource.getGroupIndex() + 1)
                    return
                }
            }
            mPlayCompletionBlock?.invoke()
        }
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
        super.release()
        lastPlayPosition = 0
        playerControlView.clearMessage()
    }

    override fun destroy() {

    }

    private fun onSettingHidden() {
        hideController()
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        clearFocus()
        requestFocus()
        // 恢复正常焦点分发，避免后续控制条显示时丢失焦点能力
        post { descendantFocusability = FOCUS_AFTER_DESCENDANTS }
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
    fun addExtendTrack(track: VideoTrackBean) {
        mControlWrapper.addTrack(track)
    }

    /**
     * 设置上次播放位置
     */
    fun setLastPosition(position: Long) {
        lastPlayPosition = position
    }

    /**
     * 设置上次播放速度
     */
    fun setLastPlaySpeed(speed: Float) {
        lastVideoSpeed = speed
    }

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
        playerPopupControlView.setExitPlayerObserver(block)
    }

    /**
     * 进入悬浮窗模式回调
     */
    fun observerEnterPopupMode(block: () -> Unit) {
        playerTopView.setEnterPopupModeObserver(block)
    }

    /**
     * 退出悬浮窗模式回调
     */
    fun observerExitPopupMode(block: () -> Unit) {
        playerPopupControlView.setExitPopupModeObserver(block)
    }

    /**
     * 监听轨道添加完成
     */
    fun observerTrackAdded(block: (VideoTrackBean) -> Unit) {
        trackAddedBlock = block
    }

    /**
     * 发送弹幕回调
     */
    @Suppress("UNUSED_PARAMETER")
    fun observerSendDanmu(block: (danmuData: SendDanmuBean) -> Unit) {
        /*
        playerBotView.setSendDanmuBlock(block)
        */
    }

    /**
     * 切换视频资源回调
     */
    fun setSwitchVideoSourceBlock(block: (Int) -> Unit) {
        this.switchVideoSourceBlock = block
        playerBotView.setSwitchVideoSourceBlock(block)
        mSettingController.setSwitchVideoSourceBlock(block)
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
        searchResult: () -> LiveData<List<DanmuEpisodeData>>
    ) {
        mSettingController.setDanmuSearch(search, download, searchResult)
    }

    /**
     * 更新字幕内容
     *
     * 由播放器调用
     */
    fun onSubtitleTextOutput(subtitle: MixedSubtitle) {
        mSubtitleController.onSubtitleTextOutput(subtitle)
    }

    private fun considerSeekToLastPlay() {
        if (lastPlayPosition <= 0)
            return

        //上次进度大于90%时，不执行自动定位进度
        val duration = mControlWrapper.getDuration()
        if (1.0 * lastPlayPosition / duration >= 0.9) {
            return
        }

        mControlWrapper.seekTo(lastPlayPosition)
        showMessage("已为你定位至：${formatDuration(lastPlayPosition)}", MessageTime.LONG)
        lastPlayPosition = 0
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
        }
    }

    private fun Context.isTelevisionUiMode(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val currentModeType = uiModeManager?.currentModeType
            ?: (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
        return currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
