package com.xyoye.data_component.bean.subtitle

import com.xyoye.data_component.enums.SubtitleFrameStatus

data class TelemetrySample(
    val timestampMs: Long,
    val subtitlePtsMs: Long,
    val renderLatencyMs: Double,
    val uploadLatencyMs: Double,
    val compositeLatencyMs: Double? = null,
    val frameStatus: SubtitleFrameStatus,
    val dropReason: String? = null,
    val cpuUsagePct: Double? = null,
    val gpuOverutilized: Boolean? = null,
    val vsyncMiss: Boolean? = null
)
