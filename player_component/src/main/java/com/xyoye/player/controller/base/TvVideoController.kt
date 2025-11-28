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

    private var pendingSeekStartPosition: Long? = null
    private var pendingSeekOffset: Long = 0L
    private val pendingSeekRunnable = Runnable { commitPendingSeek() }

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
        if (duration <= 0) {
            return
        }

        if (pendingSeekStartPosition == null) {
            pendingSeekStartPosition = mControlWrapper.getCurrentPosition()
            pendingSeekOffset = 0L
            startSeekSlide()
        }

        pendingSeekOffset += offset

        val targetPosition = calculateTargetPosition(duration)
        updateSeekSlide(targetPosition, duration)

        removeCallbacks(pendingSeekRunnable)
        postDelayed(pendingSeekRunnable, SEEK_DEBOUNCE_MS)
    }

    private fun commitPendingSeek() {
        if (pendingSeekStartPosition == null) {
            return
        }
        val duration = mControlWrapper.getDuration()
        if (duration <= 0) {
            resetPendingSeek()
            return
        }
        val targetPosition = calculateTargetPosition(duration)

        finishSeekSlide()
        mControlWrapper.seekTo(targetPosition)

        resetPendingSeek()
    }

    private fun startSeekSlide() {
        for (entry in mControlComponents.entries) {
            val view = entry.key
            if (view is InterGestureView) {
                view.onStartSlide()
            }
        }
    }

    private fun updateSeekSlide(targetPosition: Long, duration: Long) {
        val currentPosition = pendingSeekStartPosition ?: return
        for (entry in mControlComponents.entries) {
            val view = entry.key
            if (view is InterGestureView) {
                view.onPositionChange(targetPosition, currentPosition, duration)
            }
        }
    }

    private fun finishSeekSlide() {
        for (entry in mControlComponents.entries) {
            val view = entry.key
            if (view is InterGestureView) {
                view.onStopSlide()
            }
        }
    }

    private fun resetPendingSeek() {
        pendingSeekStartPosition = null
        pendingSeekOffset = 0L
        removeCallbacks(pendingSeekRunnable)
    }

    private fun calculateTargetPosition(duration: Long): Long {
        val startPosition = pendingSeekStartPosition ?: mControlWrapper.getCurrentPosition()
        return (startPosition + pendingSeekOffset).coerceIn(0L, duration)
    }

    companion object {
        private const val SEEK_DEBOUNCE_MS = 300L
    }
}
