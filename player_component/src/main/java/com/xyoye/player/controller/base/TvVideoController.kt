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
    private val pendingSeekRunnable = Runnable { commitPendingSeek() }
    private var handlingFromDispatch = false
    protected var actionHandler: ((PlayerAction) -> Unit)? = null

    init {
        applyDpadFocusable(enabled = true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
            handlingFromDispatch = true
            val state = currentUiState()
            val dispatchResult = remoteKeyDispatcher.onKeyDown(event.keyCode, state)
            return try {
                when (dispatchResult) {
                    RemoteKeyDispatcher.DispatchResult.CONSUMED -> true
                    RemoteKeyDispatcher.DispatchResult.PASS_TO_CONTROL -> super.dispatchKeyEvent(event)
                    RemoteKeyDispatcher.DispatchResult.IGNORED -> super.dispatchKeyEvent(event)
                }
            } finally {
                handlingFromDispatch = false
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (handlingFromDispatch) {
            return super.onKeyDown(keyCode, event)
        }
        val state = currentUiState()
        val dispatchResult = remoteKeyDispatcher.onKeyDown(keyCode, state)
        return when (dispatchResult) {
            RemoteKeyDispatcher.DispatchResult.CONSUMED -> true
            RemoteKeyDispatcher.DispatchResult.PASS_TO_CONTROL -> mControlWrapper.onKeyDown(keyCode, event)
            RemoteKeyDispatcher.DispatchResult.IGNORED -> false
        }
    }

    override fun hideController() {
        super.hideController()
        clearFocus()
        requestDefaultFocus()
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
            isPopupMode = isPopupMode(),
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
        if (!mControlWrapper.isUserSeekAllowed()) {
            resetPendingSeek()
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
        removeCallbacks(pendingSeekRunnable)
    }

    private fun calculateTargetPosition(duration: Long): Long {
        val startPosition = pendingSeekStartPosition ?: mControlWrapper.getCurrentPosition()
        return (startPosition + pendingSeekOffset).coerceIn(0L, duration)
    }

    companion object {
        private const val SEEK_DEBOUNCE_MS = 800L
    }
}
