package com.xyoye.common_component.subtitle.pipeline

import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode
import com.xyoye.data_component.enums.SubtitleViewType
import com.xyoye.data_component.media3.telemetry.subtitle.FallbackEvent
import com.xyoye.data_component.media3.telemetry.subtitle.SubtitleOutputTarget
import com.xyoye.data_component.media3.telemetry.subtitle.SubtitlePipelineState
import com.xyoye.data_component.media3.telemetry.subtitle.TelemetrySample
import com.xyoye.data_component.media3.telemetry.subtitle.TelemetrySnapshot

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
            recoverable =
                when (targetMode) {
                    SubtitlePipelineMode.GPU_GL -> true
                    SubtitlePipelineMode.FALLBACK_CPU -> resolvedReason != SubtitlePipelineFallbackReason.UNSUPPORTED_GPU
                },
        )
    }
}
