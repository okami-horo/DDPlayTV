package com.xyoye.common_component.services

import com.alibaba.android.arouter.facade.template.IProvider
import com.xyoye.common_component.playback.handoff.PlaybackCommand
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackStateView
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.handoff.PlaybackVerificationAnswer
import com.xyoye.common_component.playback.handoff.PlaybackVerificationResult
import kotlinx.coroutines.flow.StateFlow

/** Player owns the implementation. Callers cannot read or mutate the persistent handoff store. */
interface PlaybackRequestService : IProvider {
    /** Capture and validate a detached snapshot of input collections before asynchronous work. */
    suspend fun submit(input: PlaybackRequestInput): PlaybackSubmissionResult

    fun observePlayback(): StateFlow<PlaybackStateView?>

    suspend fun dispatch(command: PlaybackCommand): PlaybackOperationResult

    suspend fun verificationChallenge(verificationId: String): PlaybackVerificationResult

    suspend fun completeVerification(
        verificationId: String,
        answer: PlaybackVerificationAnswer
    ): PlaybackSubmissionResult

    suspend fun cancelVerification(verificationId: String): PlaybackOperationResult
}
