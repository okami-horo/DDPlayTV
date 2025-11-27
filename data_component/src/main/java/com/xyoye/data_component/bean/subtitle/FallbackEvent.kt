package com.xyoye.data_component.bean.subtitle

import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode

data class FallbackEvent(
    val timestampMs: Long,
    val fromMode: SubtitlePipelineMode,
    val toMode: SubtitlePipelineMode,
    val reason: SubtitlePipelineFallbackReason,
    val surfaceId: String? = null,
    val recoverable: Boolean = true
)
