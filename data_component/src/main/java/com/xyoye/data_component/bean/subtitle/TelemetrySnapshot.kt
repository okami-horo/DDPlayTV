package com.xyoye.data_component.bean.subtitle

import com.xyoye.data_component.enums.SubtitlePipelineMode

data class TelemetrySnapshot(
    val windowMs: Long? = null,
    val renderedFrames: Int = 0,
    val droppedFrames: Int = 0,
    val vsyncHitRate: Double? = null,
    val cpuPeakPct: Double? = null,
    val mode: SubtitlePipelineMode? = null,
    val lastFallback: FallbackEvent? = null
)
