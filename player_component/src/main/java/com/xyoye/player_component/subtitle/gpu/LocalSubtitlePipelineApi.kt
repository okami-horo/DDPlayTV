package com.xyoye.player_component.subtitle.gpu

import com.xyoye.common_component.log.SubtitleTelemetryLogger
import com.xyoye.common_component.network.subtitle.FallbackCommand
import com.xyoye.common_component.network.subtitle.PipelineInitRequest
import com.xyoye.common_component.network.subtitle.PipelineStatusResponse
import com.xyoye.common_component.network.subtitle.SubtitlePipelineApi
import com.xyoye.data_component.bean.subtitle.FallbackEvent
import com.xyoye.data_component.bean.subtitle.SubtitleOutputTarget
import com.xyoye.data_component.bean.subtitle.SubtitlePipelineState
import com.xyoye.data_component.bean.subtitle.TelemetrySample
import com.xyoye.data_component.bean.subtitle.TelemetrySnapshot
import com.xyoye.data_component.enums.SubtitlePipelineMode
import com.xyoye.data_component.enums.SubtitlePipelineStatus
import com.xyoye.data_component.repository.subtitle.SubtitleTelemetryRepository

/**
 * In-process implementation of SubtitlePipelineApi to back the GPU renderer.
 */
class LocalSubtitlePipelineApi(
    private val telemetryRepository: SubtitleTelemetryRepository = SubtitleTelemetryRepository()
) : SubtitlePipelineApi {
    private var state: SubtitlePipelineState? = null
    private var outputTarget: SubtitleOutputTarget? = null

    override suspend fun init(request: PipelineInitRequest): SubtitlePipelineState {
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
                fallbackReason = command.reason,
                lastRecoverAt =
                    if (command.targetMode ==
                        SubtitlePipelineMode.GPU_GL
                    ) {
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
                    recoverable = command.targetMode == SubtitlePipelineMode.GPU_GL,
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
