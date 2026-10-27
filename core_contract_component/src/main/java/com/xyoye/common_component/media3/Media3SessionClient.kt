package com.xyoye.common_component.media3

import com.xyoye.common_component.playback.handoff.PlaybackCommand
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult
import com.xyoye.common_component.playback.handoff.PlaybackStateView
import kotlinx.coroutines.flow.StateFlow

/** Platform adapter reads the player's complete projection and forwards version-bound commands. */
interface Media3SessionClient {
    fun playback(): StateFlow<PlaybackStateView?>

    suspend fun dispatch(command: PlaybackCommand): PlaybackOperationResult
}
