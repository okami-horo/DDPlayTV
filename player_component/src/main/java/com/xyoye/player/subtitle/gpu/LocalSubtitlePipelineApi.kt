package com.xyoye.player.subtitle.gpu

import com.xyoye.common_component.log.SubtitleTelemetryLogger
import com.xyoye.common_component.subtitle.pipeline.FallbackCommand
import com.xyoye.common_component.subtitle.pipeline.PipelineInitRequest
import com.xyoye.common_component.subtitle.pipeline.PipelineStatusResponse
import com.xyoye.common_component.subtitle.pipeline.SubtitlePipelineApi
import com.xyoye.common_component.telemetry.subtitle.SubtitleTelemetryRepository
import com.xyoye.data_component.media3.telemetry.subtitle.FallbackEvent
import com.xyoye.data_component.media3.telemetry.subtitle.SubtitleOutputTarget
import com.xyoye.data_component.media3.telemetry.subtitle.SubtitlePipelineState
import com.xyoye.data_component.media3.telemetry.subtitle.TelemetrySample
import com.xyoye.data_component.media3.telemetry.subtitle.TelemetrySnapshot
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode
import com.xyoye.data_component.enums.SubtitlePipelineStatus

/**
 * In-process implementation of SubtitlePipelineApi to back the GPU renderer.
 */
class LocalSubtitlePipelineApi(
    private val telemetryRepository: SubtitleTelemetryRepository = SubtitleTelemetryRepository()
) : SubtitlePipelineApi {
    private var state: SubtitlePipelineState? = null
    private var outputTarget: SubtitleOutputTarget? = null

    override suspend fun init(request: PipelineInitRequest): SubtitlePipelineState {
        val previous = state
        outputTarget =
            SubtitleOutputTarget(
                viewType = request.viewType,
                width = request.width,
                height = request.height,
                scale = request.scale,
                rotation = request.rotation,
                colorFormat = request.colorFormat,
                supportsHardwareBuffer = request.supportsHardwareBuffer,
                vsyncId = request.vsyncId,
            )
        val newState =
            SubtitlePipelineState(
                mode = SubtitlePipelineMode.GPU_GL,
                status = SubtitlePipelineStatus.Active,
                surfaceId = request.surfaceId,
                fallbackReason = null,
                lastError = null,
                lastRecoverAt = previous?.lastRecoverAt,
                telemetryEnabled = request.telemetryEnabled,
            )
        state = newState
        telemetryRepository.updateState(newState)
        SubtitleTelemetryLogger.logState(newState)
        return newState
    }

    override suspend fun status(): PipelineStatusResponse = PipelineStatusResponse(state, outputTarget)

    override suspend fun fallback(command: FallbackCommand): SubtitlePipelineState {
        val previous = state
        val fallbackReason =
            when (command.targetMode) {
                SubtitlePipelineMode.GPU_GL -> previous?.fallbackReason ?: command.reason
                SubtitlePipelineMode.FALLBACK_CPU -> command.reason
            }
        val recoverable =
            when (command.targetMode) {
                SubtitlePipelineMode.GPU_GL -> true
                SubtitlePipelineMode.FALLBACK_CPU -> command.reason != SubtitlePipelineFallbackReason.UNSUPPORTED_GPU
            }
        val updated =
            (
                previous ?: SubtitlePipelineState(
                    mode = SubtitlePipelineMode.GPU_GL,
                    status = SubtitlePipelineStatus.Initializing,
                    surfaceId = null,
                )
            ).copy(
                mode = command.targetMode,
                status =
                    when (command.targetMode) {
                        SubtitlePipelineMode.GPU_GL -> SubtitlePipelineStatus.Initializing
                        SubtitlePipelineMode.FALLBACK_CPU -> SubtitlePipelineStatus.Degraded
                    },
                fallbackReason = fallbackReason,
                lastRecoverAt =
                    if (command.targetMode == SubtitlePipelineMode.GPU_GL) {
                        System.currentTimeMillis()
                    } else {
                        previous?.lastRecoverAt
                    },
            )
        state = updated
        telemetryRepository.updateState(updated)
        command.reason?.let { reason ->
            val event =
                FallbackEvent(
                    timestampMs = System.currentTimeMillis(),
                    fromMode = previous?.mode ?: SubtitlePipelineMode.GPU_GL,
                    toMode = command.targetMode,
                    reason = reason,
                    surfaceId = previous?.surfaceId,
                    recoverable = recoverable,
                )
            telemetryRepository.recordFallback(event)
            SubtitleTelemetryLogger.logFallback(event)
        }
        SubtitleTelemetryLogger.logState(updated)
        return updated
    }

    override suspend fun submitTelemetry(sample: TelemetrySample) {
        telemetryRepository.submit(sample, state)
    }

    override suspend fun latestTelemetry(): TelemetrySnapshot? = telemetryRepository.latestSnapshot()
}
