package com.xyoye.player.controller.base

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import com.xyoye.player.controller.video.InterGestureView
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player.remote.RemoteKeyDispatcher

/**
 * Created by xyoye on 2021/5/30.
 */

abstract class TvVideoController(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseVideoController(context, attrs, defStyleAttr) {

    private val remoteKeyDispatcher = RemoteKeyDispatcher(object : RemoteKeyDispatcher.RemoteKeyAction {
        override fun togglePlay() {
            this@TvVideoController.togglePlay()
        }

        override fun seekBy(offsetMs: Long) {
            changePosition(offsetMs)
        }

        override fun showController() {
            this@TvVideoController.showController(true)
        }

        override fun openPlayerSettings() {
            mControlWrapper.showSettingView(SettingViewType.PLAYER_SETTING)
        }

        override fun openEpisodePanel(): Boolean {
            val videoSource = mControlWrapper.getVideoSource()
            if (videoSource.getGroupSize() <= 1) {
                return false
            }
            mControlWrapper.showSettingView(SettingViewType.SWITCH_VIDEO_SOURCE)
            return true
        }
    })

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val state = currentUiState()
        val dispatchResult = remoteKeyDispatcher.onKeyDown(keyCode, state)
        return when (dispatchResult) {
            RemoteKeyDispatcher.DispatchResult.CONSUMED -> true
            RemoteKeyDispatcher.DispatchResult.PASS_TO_CONTROL -> mControlWrapper.onKeyDown(keyCode, event)
            RemoteKeyDispatcher.DispatchResult.IGNORED -> false
        }
    }

    private fun currentUiState(): RemoteKeyDispatcher.UiState {
        val focusView = findFocus()
        val hasFocus = focusView != null && focusView !== this
        return RemoteKeyDispatcher.UiState(
            isLocked = isLocked(),
            isControllerShowing = isControllerShowing(),
            isSettingShowing = mControlWrapper.isSettingViewShowing(),
            isPopupMode = isPopupMode(),
            hasControllerFocus = hasFocus
        )
    }

    private fun changePosition(offset: Long) {
        val duration = mControlWrapper.getDuration()
        val currentPosition = mControlWrapper.getCurrentPosition()
        val newPosition = currentPosition + offset

        for (entry in mControlComponents.entries) {
            val view = entry.key
            if (view is InterGestureView) {
                view.onStartSlide()
                view.onPositionChange(newPosition, currentPosition, duration)
                view.onStopSlide()
            }
        }
        mControlWrapper.seekTo(newPosition)
    }
}
