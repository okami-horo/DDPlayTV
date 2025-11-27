package com.xyoye.data_component.bean.subtitle

import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode
import com.xyoye.data_component.enums.SubtitlePipelineStatus

data class SubtitlePipelineState(
    val mode: SubtitlePipelineMode,
    val status: SubtitlePipelineStatus,
    val surfaceId: String? = null,
    val eglContextId: String? = null,
    val fallbackReason: SubtitlePipelineFallbackReason? = null,
    val lastError: String? = null,
    val lastRecoverAt: Long? = null,
    val telemetryEnabled: Boolean = true
)
