package com.xyoye.player.controller.base

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import com.xyoye.common_component.focus.applyDpadFocusable
import com.xyoye.common_component.focus.requestDefaultFocus
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player.controller.action.PlayerAction
import com.xyoye.player.controller.video.InterGestureView
import com.xyoye.player.remote.RemoteKeyDispatcher

/**
 * Created by xyoye on 2021/5/30.
 */

abstract class TvVideoController(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseVideoController(context, attrs, defStyleAttr) {
    private val remoteKeyDispatcher =
        RemoteKeyDispatcher(
            object : RemoteKeyDispatcher.RemoteKeyAction {
                override fun togglePlay() {
                    dispatchAction(PlayerAction.TogglePlay)
                }

                override fun seekBy(offsetMs: Long) {
                    dispatchAction(PlayerAction.SeekBy(offsetMs))
                }

                override fun showController() {
                    dispatchAction(PlayerAction.ShowController)
                }

                override fun openPlayerSettings() {
                    dispatchAction(PlayerAction.OpenPlayerSettings)
                }

                override fun openEpisodePanel(): Boolean {
                    val videoSource = mControlWrapper.getVideoSource()
                    if (videoSource.getGroupSize() <= 1) {
                        return false
                    }
                    dispatchAction(PlayerAction.OpenEpisodePanel)
                    return true
                }
            },
        )

    private var pendingSeekStartPosition: Long? = null
    private var pendingSeekOffset: Long = 0L
    private var pendingSeekSource: com.xyoye.common_component.source.base.BaseVideoSource? = null
    private val pendingSeekRunnable = Runnable { commitPendingSeek() }
    private val consumedKeys = mutableSetOf<Int>()
    protected var actionHandler: ((PlayerAction) -> Unit)? = null

    init {
        applyDpadFocusable(enabled = true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && consumedKeys.remove(event.keyCode)) return true
        val isDpadKey =
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_MENU -> true
                else -> false
            }
        if (event.action == KeyEvent.ACTION_DOWN && isDpadKey) {
            val state = currentUiState()
            if (remoteKeyDispatcher.onKeyDown(event.keyCode, state, event.repeatCount) == RemoteKeyDispatcher.DispatchResult.CONSUMED) {
                consumedKeys.add(event.keyCode)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun hideController() {
        if (mControlWrapper.isSettingViewShowing()) return
        super.hideController()
        clearFocus()
        requestDefaultFocus()
    }

    override fun release() {
        cancelPendingSeek()
        super.release()
    }

    override fun destroy() {
        cancelPendingSeek()
    }

    protected fun dispatchAction(action: PlayerAction) {
        actionHandler?.invoke(action) ?: run {
            when (action) {
                is PlayerAction.SeekBy -> changePosition(action.offsetMs)
                PlayerAction.TogglePlay -> togglePlay()
                PlayerAction.ShowController -> showController(true)
                PlayerAction.OpenPlayerSettings -> mControlWrapper.showSettingView(SettingViewType.PLAYER_SETTING)
                PlayerAction.OpenEpisodePanel -> mControlWrapper.showSettingView(SettingViewType.SWITCH_VIDEO_SOURCE)
                PlayerAction.NextSource -> {
                    val videoSource = mControlWrapper.getVideoSource()
                    if (videoSource.hasNextSource()) {
                        mControlWrapper.showSettingView(SettingViewType.SWITCH_VIDEO_SOURCE)
                    } else {
                        showController(true)
                    }
                }
                PlayerAction.PreviousSource -> {
                    val videoSource = mControlWrapper.getVideoSource()
                    if (videoSource.hasPreviousSource()) {
                        mControlWrapper.showSettingView(SettingViewType.SWITCH_VIDEO_SOURCE)
                    } else {
                        showController(true)
                    }
                }
                PlayerAction.OpenSourceList -> mControlWrapper.showSettingView(SettingViewType.SWITCH_VIDEO_SOURCE)
                PlayerAction.ToggleDanmu -> mControlWrapper.toggleDanmuVisible()
                PlayerAction.ToggleSubtitle -> mControlWrapper.toggleSubtitleVisible()
            }
        }
    }

    private fun currentUiState(): RemoteKeyDispatcher.UiState {
        val focusView = findFocus()
        val controllerVisible = isControllerShowing()
        val hasFocus = controllerVisible && focusView != null && focusView !== this && focusView.isShown
        return RemoteKeyDispatcher.UiState(
            isLocked = isLocked(),
            isControllerShowing = controllerVisible,
            isSettingShowing = mControlWrapper.isSettingViewShowing(),
            hasControllerFocus = hasFocus,
        )
    }

    protected fun changePosition(offset: Long) {
        if (!mControlWrapper.isUserSeekAllowed()) {
            return
        }
        val duration = mControlWrapper.getDuration()
        if (duration <= 0) {
            return
        }

        if (pendingSeekStartPosition == null) {
            pendingSeekSource = mControlWrapper.getVideoSource()
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
        if (pendingSeekSource !== mControlWrapper.getVideoSource()) {
            cancelPendingSeek()
            return
        }
        if (!mControlWrapper.isUserSeekAllowed()) {
            cancelPendingSeek()
            return
        }
        val duration = mControlWrapper.getDuration()
        if (duration <= 0) {
            cancelPendingSeek()
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

    private fun updateSeekSlide(
        targetPosition: Long,
        duration: Long
    ) {
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
        pendingSeekSource = null
        removeCallbacks(pendingSeekRunnable)
    }

    fun cancelPendingSeek() {
        resetPendingSeek()
        for (entry in mControlComponents.entries) {
            (entry.key as? InterGestureView)?.onCancelSlide()
        }
    }

    private fun calculateTargetPosition(duration: Long): Long {
        val startPosition = pendingSeekStartPosition ?: mControlWrapper.getCurrentPosition()
        return (startPosition + pendingSeekOffset).coerceIn(0L, duration)
    }

    companion object {
        private const val SEEK_DEBOUNCE_MS = 800L
    }
}
