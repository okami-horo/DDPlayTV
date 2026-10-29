package com.xyoye.player.controller.video

import android.graphics.Point
import android.view.View
import com.xyoye.data_component.enums.PlayState
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.wrapper.ControlWrapper

/**
 * Created by xyoye on 2020/11/1.
 */

interface InterControllerView {
    fun attach(controlWrapper: ControlWrapper)

    fun getView(): View

    fun onVisibilityChanged(isVisible: Boolean) {
        // Optional callback: the default implementation intentionally does nothing.
    }

    fun onPlayStateChanged(playState: PlayState) {
        // Optional callback: implementations react only when they render play state.
    }

    fun onProgressChanged(
        duration: Long,
        position: Long,
    ) {
        // Optional callback: implementations that do not show progress can ignore it.
    }

    fun onLockStateChanged(isLocked: Boolean) {
        // Optional callback: implementations without lock UI can keep no-op behavior.
    }

    fun onVideoSizeChanged(videoSize: Point) {
        // Optional callback: not every control component depends on video size.
    }

    fun onTrackChanged(type: TrackType) {
        // Optional callback: components without track-related UI can ignore updates.
    }
}
