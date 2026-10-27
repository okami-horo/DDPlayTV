package com.okamihoro.ddplaytv.app.service

import com.xyoye.common_component.playback.handoff.PlaybackCommand
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult

fun interface MediaSessionCommandBridge {
    suspend fun dispatch(command: PlaybackCommand): PlaybackOperationResult
}
