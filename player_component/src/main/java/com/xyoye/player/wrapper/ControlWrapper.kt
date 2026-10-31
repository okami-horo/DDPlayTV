package com.xyoye.player.wrapper

import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.DanmakuLanguage
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.data_component.enums.TrackType

/**
 * Created by xyoye on 2020/11/1.
 *
 * 控制器包装类
 *
 * 用于实现不同控制器间的交互
 */
class ControlWrapper(
    private val mVideoPlayer: InterVideoPlayer,
    private val mController: InterVideoController,
    private val mDanmuController: InterDanmuController,
    private val mSubtitleController: InterSubtitleController,
    private val mSettingController: InterSettingController
) : InterVideoPlayer by mVideoPlayer,
    InterVideoController by mController,
    InterDanmuController by mDanmuController,
    InterSubtitleController by mSubtitleController,
    InterSettingController by mSettingController {
    override fun seekTo(timeMs: Long) {
        // 播放器
        mVideoPlayer.seekTo(timeMs)
        // 弹幕
        seekTo(timeMs, isPlaying())
        // 视图
        if (isPlaying()) {
            startProgress()
        } else {
            setProgress(timeMs)
        }
    }

    override fun setSpeed(speed: Float) {
        mVideoPlayer.setSpeed(speed)
        mDanmuController.setSpeed(speed)
    }

    override fun supportAddTrack(type: TrackType): Boolean = mVideoPlayer.supportAddTrack(type)

    override fun addTrack(track: VideoTrackBean): Boolean {
        // 如果视频播放器支持添加轨道，则直接添加
        // 否则由支持轨道的控制器添加
        val trackType = track.type
        val added =
            when {
                mVideoPlayer.supportAddTrack(trackType) -> mVideoPlayer.addTrack(track)
                mSubtitleController.supportAddTrack(trackType) -> mSubtitleController.addTrack(track)
                mDanmuController.supportAddTrack(trackType) -> mDanmuController.addTrack(track)
                else -> false
            }

        if (added) {
            // 添加轨道成功，设置轨道选中
            selectTrack(track)
            mController.setTrackAdded(track)
        }
        return added
    }

    override fun getTracks(type: TrackType): List<VideoTrackBean> {
        val tracks =
            if (mVideoPlayer.supportAddTrack(type)) {
                // 如果视频播放器支持添加轨道，则直接获取播放器的轨道
                mVideoPlayer.getTracks(type)
            } else {
                // 获取播放器的轨道和控制器的轨道
                mVideoPlayer
                    .getTracks(type)
                    .toMutableList()
                    .apply {
                        when (type) {
                            TrackType.SUBTITLE -> addAll(mSubtitleController.getTracks(type))
                            TrackType.DANMU -> addAll(mDanmuController.getTracks(type))
                            else -> Unit
                        }
                    }
            }

        // 当轨道列表不为空时（且不是视频轨），添加“禁用”轨道用于显式取消选择
        if (type != TrackType.VIDEO && tracks.isNotEmpty()) {
            val hasSelected = tracks.any { it.selected }
            val disableTrack = VideoTrackBean.disable(type = type, selected = !hasSelected)
            return listOf(disableTrack) + tracks
        }
        return tracks
    }

    override fun selectTrack(track: VideoTrackBean) {
        // 如果视频播放器支持添加轨道，则选中播放器轨道，并取消控制器中同类型轨道的选中
        // 否则由支持轨道的控制器选中，并取消播放器中同类型轨道的选中
        val trackType = track.type
        when {
            track.internal || mVideoPlayer.supportAddTrack(trackType) -> {
                mVideoPlayer.selectTrack(track)
                mSubtitleController.deselectTrack(trackType)
                mDanmuController.deselectTrack(trackType)
            }

            mSubtitleController.supportAddTrack(trackType) -> {
                mVideoPlayer.deselectTrack(trackType)
                mSubtitleController.selectTrack(track)
            }

            mDanmuController.supportAddTrack(trackType) -> {
                mVideoPlayer.deselectTrack(trackType)
                mDanmuController.selectTrack(track)
            }
        }
        mController.setTrackUpdated(trackType)
    }

    override fun deselectTrack(type: TrackType) {
        mVideoPlayer.deselectTrack(type)
        mSubtitleController.deselectTrack(type)
        mDanmuController.deselectTrack(type)
        mController.setTrackUpdated(type)
    }

    override fun setLanguage(language: DanmakuLanguage) {
        mDanmuController.setLanguage(language)
        mDanmuController.seekTo(getCurrentPosition(), isPlaying())
    }

    override fun updateSubtitleOffsetTime() {
        mSubtitleController.updateSubtitleOffsetTime()
        mVideoPlayer.updateSubtitleOffsetTime()
    }

    override fun showSettingView(
        viewType: SettingViewType,
        extra: Any?
    ) {
        if (isLocked()) {
            hideController()
            return
        }
        // Modal panels retain the visible control that opened them so BACK can restore it.
        mSettingController.showSettingView(viewType, extra)
    }

    /**
     * 切换播放状态
     */
    fun togglePlay() {
        if (isPlaying()) {
            pause()
        } else {
            start()
        }
    }

    /**
     * 切换视图锁定状态
     */
    fun toggleLockState() {
        // 锁定入口在当前版本默认隐藏，保留空实现用于兼容旧调用方。
    }

    /**
     * 切换视图显示状态
     */
    fun toggleVisible() {
        if (isSettingViewShowing()) {
            hideSettingView()
            return
        }
        if (isControllerShowing()) {
            hideController()
        } else {
            showController()
        }
    }

    fun isUserSeekAllowed(): Boolean = isSeekable() && !isLive()
}
