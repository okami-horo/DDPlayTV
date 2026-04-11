package com.xyoye.common_component.log

import com.xyoye.common_component.log.model.LogModule
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode

/**
 * Structured logger for GPU subtitle fallback/recovery events to keep logcat
 * readable while capturing context for debugging.
 */
object SubtitleFallbackReporter {
    private const val TAG = "SUB-FALLBACK"

    fun onDegraded(
        reason: SubtitlePipelineFallbackReason,
        surfaceId: String?,
        recoverable: Boolean
    ) {
        LogFacade.w(
            LogModule.SUBTITLE,
            TAG,
            "degraded reason=${reason.name} recoverable=$recoverable surface=$surfaceId",
        )
    }

    fun onRecoveryPending(
        reason: SubtitlePipelineFallbackReason?,
        surfaceId: String?,
        targetMode: SubtitlePipelineMode = SubtitlePipelineMode.GPU_GL
    ) {
        LogFacade.i(
            LogModule.SUBTITLE,
            TAG,
            "recovery_pending target=${targetMode.name} trigger=${reason?.name} surface=$surfaceId",
        )
    }

    fun onRecoveryOutcome(
        reason: SubtitlePipelineFallbackReason?,
        succeeded: Boolean,
        surfaceId: String?,
        targetMode: SubtitlePipelineMode = SubtitlePipelineMode.GPU_GL
    ) {
        val message =
            "recovery_result target=${targetMode.name} success=$succeeded trigger=${reason?.name} surface=$surfaceId"
        if (succeeded) {
            LogFacade.i(LogModule.SUBTITLE, TAG, message)
        } else {
            LogFacade.w(LogModule.SUBTITLE, TAG, message)
        }
    }
}
