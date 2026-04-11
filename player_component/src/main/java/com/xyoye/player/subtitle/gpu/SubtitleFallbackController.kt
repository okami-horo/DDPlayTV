package com.xyoye.player.subtitle.gpu

import com.xyoye.common_component.log.SubtitleFallbackReporter
import com.xyoye.data_component.media3.telemetry.subtitle.SubtitlePipelineState
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode

/**
 * Maps `/subtitle/pipeline/fallback` transitions to the in-process controller
 * and reports the outcome for downstream recovery handlers.
 */
class SubtitleFallbackController(
    private val pipelineController: SubtitlePipelineController,
    private val reporter: SubtitleFallbackReporter = SubtitleFallbackReporter
) {
    suspend fun enterDegraded(reason: SubtitlePipelineFallbackReason): SubtitlePipelineState? {
        val state = pipelineController.fallback(SubtitlePipelineMode.FALLBACK_CPU, reason)
        state?.let {
            reporter.onDegraded(
                reason = reason,
                surfaceId = it.surfaceId,
                recoverable = reason != SubtitlePipelineFallbackReason.UNSUPPORTED_GPU,
            )
        }
        return state
    }

    suspend fun markRecoveryPending(reason: SubtitlePipelineFallbackReason = SubtitlePipelineFallbackReason.UNKNOWN): SubtitlePipelineState? {
        val state = pipelineController.fallback(SubtitlePipelineMode.GPU_GL, reason)
        state?.let { reporter.onRecoveryPending(reason = reason, surfaceId = it.surfaceId) }
        return state
    }

    fun reportRecoveryOutcome(
        reason: SubtitlePipelineFallbackReason?,
        succeeded: Boolean,
        surfaceId: String? = currentState()?.surfaceId
    ) {
        reporter.onRecoveryOutcome(reason = reason, succeeded = succeeded, surfaceId = surfaceId)
    }

    fun currentState(): SubtitlePipelineState? = pipelineController.currentState()
}
