package com.xyoye.player.subtitle.gpu

import com.xyoye.common_component.log.SubtitleFallbackReporter
import com.xyoye.data_component.bean.subtitle.SubtitlePipelineState
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
    suspend fun forceFallback(reason: SubtitlePipelineFallbackReason): SubtitlePipelineState? {
        val state = pipelineController.fallback(SubtitlePipelineMode.FALLBACK_CPU, reason)
        state?.let { reporter.onFallback(reason, it.surfaceId, recoverable = false) }
        return state
    }

    suspend fun resumeGpu(reason: SubtitlePipelineFallbackReason = SubtitlePipelineFallbackReason.UNKNOWN): SubtitlePipelineState? {
        val state = pipelineController.fallback(SubtitlePipelineMode.GPU_GL, reason)
        state?.let { reporter.onRecoveryAttempt(reason, succeeded = true, surfaceId = it.surfaceId) }
        return state
    }

    fun currentState(): SubtitlePipelineState? = pipelineController.currentState()
}
