package com.okamihoro.ddplaytv.app.service

import com.xyoye.common_component.playback.handoff.PlaybackAction
import com.xyoye.common_component.playback.handoff.PlaybackCommand
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackStateView

/** Platform policy is an additional guard; the player remains the authority for all commands. */
class Media3BackgroundCoordinator(
    private val bridge: MediaSessionCommandBridge
) {
    suspend fun dispatch(
        command: PlaybackCommand,
        state: PlaybackStateView?
    ): PlaybackOperationResult {
        if (state?.version != command.expected) return PlaybackOperationResult.Rejected(PlaybackPreparationFailure.RequestSuperseded)
        if (command.action == PlaybackAction.EnterBackground || command.action == PlaybackAction.EnterPictureInPicture) {
            return PlaybackOperationResult.Rejected(PlaybackPreparationFailure.UnsupportedSource)
        }
        return bridge.dispatch(command)
    }
}
