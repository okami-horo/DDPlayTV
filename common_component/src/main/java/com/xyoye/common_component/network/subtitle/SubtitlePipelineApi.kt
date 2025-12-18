package com.xyoye.common_component.network.subtitle

import com.xyoye.data_component.bean.subtitle.FallbackEvent
import com.xyoye.data_component.bean.subtitle.SubtitleOutputTarget
import com.xyoye.data_component.bean.subtitle.SubtitlePipelineState
import com.xyoye.data_component.bean.subtitle.TelemetrySample
import com.xyoye.data_component.bean.subtitle.TelemetrySnapshot
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode
import com.xyoye.data_component.enums.SubtitleViewType

/**
 * In-process façade for the GPU subtitle pipeline.
 */
interface SubtitlePipelineApi {
    suspend fun init(request: PipelineInitRequest): SubtitlePipelineState

    suspend fun status(): PipelineStatusResponse

    suspend fun fallback(command: FallbackCommand): SubtitlePipelineState

    suspend fun submitTelemetry(sample: TelemetrySample)

    suspend fun latestTelemetry(): TelemetrySnapshot?
}

data class PipelineInitRequest(
    val surfaceId: String,
    val viewType: SubtitleViewType,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val scale: Float = 1f,
    val colorFormat: String? = null,
    val supportsHardwareBuffer: Boolean = false,
    val vsyncId: Long? = null,
    val telemetryEnabled: Boolean = true
)

data class PipelineStatusResponse(
    val state: SubtitlePipelineState? = null,
    val outputTarget: SubtitleOutputTarget? = null
)

data class FallbackCommand(
    val targetMode: SubtitlePipelineMode,
    val reason: SubtitlePipelineFallbackReason? = null
) {
    fun toEvent(
        fromMode: SubtitlePipelineMode,
        surfaceId: String?
    ): FallbackEvent? {
        val resolvedReason = reason ?: return null
        val timestamp = System.currentTimeMillis()
        return FallbackEvent(
            timestampMs = timestamp,
            fromMode = fromMode,
            toMode = targetMode,
            reason = resolvedReason,
            surfaceId = surfaceId,
            recoverable = targetMode == SubtitlePipelineMode.GPU_GL,
        )
    }
}
