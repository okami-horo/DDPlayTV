package com.xyoye.player.remote

import android.view.KeyEvent

/**
 * Routes DPAD/Menu key events to consistent player actions on TV devices.
 */
class RemoteKeyDispatcher(
    private val remoteAction: RemoteKeyAction,
    private val seekOffsetMs: Long = DEFAULT_SEEK_OFFSET_MS
) {

    data class UiState(
        val isLocked: Boolean,
        val isControllerShowing: Boolean,
        val isSettingShowing: Boolean,
        val isPopupMode: Boolean,
        val hasControllerFocus: Boolean
    )

    enum class DispatchResult {
        CONSUMED,
        PASS_TO_CONTROL,
        IGNORED
    }

    interface RemoteKeyAction {
        fun togglePlay()
        fun seekBy(offsetMs: Long)
        fun showController()
        fun openPlayerSettings()
        fun openEpisodePanel(): Boolean
    }

    fun onKeyDown(keyCode: Int, state: UiState): DispatchResult {
        if (state.isPopupMode) {
            return DispatchResult.IGNORED
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> handleCenter(state)
            KeyEvent.KEYCODE_DPAD_LEFT -> handleSeek(state, -seekOffsetMs)
            KeyEvent.KEYCODE_DPAD_RIGHT -> handleSeek(state, seekOffsetMs)
            KeyEvent.KEYCODE_DPAD_UP -> handleUp(state)
            KeyEvent.KEYCODE_DPAD_DOWN -> handleDown(state)
            KeyEvent.KEYCODE_MENU -> handleMenu()
            else -> DispatchResult.IGNORED
        }
    }

    private fun handleCenter(state: UiState): DispatchResult {
        if (state.isLocked) {
            remoteAction.showController()
            return DispatchResult.CONSUMED
        }
        if (state.isSettingShowing) {
            return DispatchResult.PASS_TO_CONTROL
        }
        if (state.isControllerShowing && state.hasControllerFocus) {
            return DispatchResult.PASS_TO_CONTROL
        }
        remoteAction.togglePlay()
        return DispatchResult.CONSUMED
    }

    private fun handleSeek(state: UiState, offsetMs: Long): DispatchResult {
        if (state.isSettingShowing) {
            return DispatchResult.PASS_TO_CONTROL
        }
        if (state.isLocked) {
            remoteAction.showController()
            return DispatchResult.CONSUMED
        }
        if (state.isControllerShowing) {
            remoteAction.showController()
            return DispatchResult.PASS_TO_CONTROL
        }
        remoteAction.seekBy(offsetMs)
        return DispatchResult.CONSUMED
    }

    private fun handleUp(state: UiState): DispatchResult {
        if (state.isSettingShowing) {
            return DispatchResult.PASS_TO_CONTROL
        }
        if (state.isControllerShowing) {
            return DispatchResult.PASS_TO_CONTROL
        }
        if (state.isLocked) {
            remoteAction.showController()
            return DispatchResult.CONSUMED
        }
        val opened = remoteAction.openEpisodePanel()
        if (!opened) {
            remoteAction.showController()
        }
        return DispatchResult.CONSUMED
    }

    private fun handleDown(state: UiState): DispatchResult {
        if (state.isSettingShowing) {
            return DispatchResult.PASS_TO_CONTROL
        }
        if (state.isControllerShowing) {
            return DispatchResult.PASS_TO_CONTROL
        }
        remoteAction.showController()
        return DispatchResult.CONSUMED
    }

    private fun handleMenu(): DispatchResult {
        remoteAction.openPlayerSettings()
        return DispatchResult.CONSUMED
    }

    companion object {
        private const val DEFAULT_SEEK_OFFSET_MS = 10_000L
    }
}
