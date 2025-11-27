package com.xyoye.common_component.log

import com.xyoye.common_component.utils.DDLog
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode

/**
 * Structured logger for GPU subtitle fallback/recovery events to keep logcat
 * readable while capturing context for debugging.
 */
object SubtitleFallbackReporter {
    private const val TAG = "SUB-FALLBACK"

    fun onFallback(
        reason: SubtitlePipelineFallbackReason,
        surfaceId: String?,
        recoverable: Boolean
    ) {
        DDLog.w(
            TAG,
            "fallback reason=${reason.name} recoverable=$recoverable surface=$surfaceId"
        )
    }

    fun onRecoveryAttempt(
        reason: SubtitlePipelineFallbackReason?,
        succeeded: Boolean,
        surfaceId: String?,
        targetMode: SubtitlePipelineMode = SubtitlePipelineMode.GPU_GL
    ) {
        DDLog.i(
            TAG,
            "recovery target=${targetMode.name} success=$succeeded reason=${reason?.name} surface=$surfaceId"
        )
    }
}
