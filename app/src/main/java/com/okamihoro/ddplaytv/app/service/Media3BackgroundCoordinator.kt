package com.okamihoro.ddplaytv.app.service

import com.xyoye.common_component.playback.handoff.PlaybackAction
import com.xyoye.common_component.playback.handoff.PlaybackCommand
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackStateView
import com.xyoye.data_component.media3.entity.Media3Capability

/** Platform policy is an additional guard; the player remains the authority for all commands. */
class Media3BackgroundCoordinator(
    private val bridge: MediaSessionCommandBridge
) {
    suspend fun dispatch(
        command: PlaybackCommand,
        state: PlaybackStateView?,
        isTelevisionUiMode: Boolean
    ): PlaybackOperationResult {
        if (state?.version != command.expected) return PlaybackOperationResult.Rejected(PlaybackPreparationFailure.RequestSuperseded)
        val capability =
            when (command.action) {
                PlaybackAction.EnterBackground -> Media3Capability.BACKGROUND
                PlaybackAction.EnterPictureInPicture -> Media3Capability.PIP
                else -> null
            }
        if (capability != null && (isTelevisionUiMode || capability !in state.capabilities)) {
            return PlaybackOperationResult.Rejected(PlaybackPreparationFailure.UnsupportedSource)
        }
        return bridge.dispatch(command)
    }
}
