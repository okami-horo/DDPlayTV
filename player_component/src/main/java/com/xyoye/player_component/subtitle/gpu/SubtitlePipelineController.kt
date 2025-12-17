package com.xyoye.player_component.subtitle.gpu

import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.SubtitleTelemetryLogger
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.network.subtitle.FallbackCommand
import com.xyoye.common_component.network.subtitle.PipelineInitRequest
import com.xyoye.common_component.network.subtitle.PipelineStatusResponse
import com.xyoye.common_component.network.subtitle.SubtitlePipelineApi
import com.xyoye.data_component.bean.subtitle.SubtitleOutputTarget
import com.xyoye.data_component.bean.subtitle.SubtitlePipelineState
import com.xyoye.data_component.bean.subtitle.TelemetrySample
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates calls to the in-process subtitle pipeline façade and
 * keeps a cached state for render orchestration.
 */
class SubtitlePipelineController(
    private val api: SubtitlePipelineApi,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val cachedState = AtomicReference<SubtitlePipelineState?>()
    private val cachedSurfaceId = AtomicReference<String?>()

    suspend fun init(
        surfaceId: String,
        target: SubtitleOutputTarget,
        telemetryEnabled: Boolean = true
    ): SubtitlePipelineState =
        withContext(dispatcher) {
            cachedSurfaceId.set(surfaceId)
            val request =
                PipelineInitRequest(
                    surfaceId = surfaceId,
                    viewType = target.viewType,
                    width = target.width,
                    height = target.height,
                    rotation = target.rotation,
                    scale = target.scale,
                    colorFormat = target.colorFormat,
                    supportsHardwareBuffer = target.supportsHardwareBuffer,
                    vsyncId = target.vsyncId,
                    telemetryEnabled = telemetryEnabled,
                )
            val newState = api.init(request).copy(telemetryEnabled = telemetryEnabled)
            cachedState.set(newState)
            SubtitleTelemetryLogger.logState(newState)
            newState
        }

    suspend fun status(): PipelineStatusResponse =
        withContext(dispatcher) {
            val response = api.status()
            response.state?.let { cachedState.set(it) }
            response
        }

    suspend fun fallback(
        targetMode: SubtitlePipelineMode,
        reason: SubtitlePipelineFallbackReason?
    ): SubtitlePipelineState? =
        withContext(dispatcher) {
            val previousMode = cachedState.get()?.mode ?: SubtitlePipelineMode.GPU_GL
            val command = FallbackCommand(targetMode, reason)
            runCatching { api.fallback(command) }
                .onSuccess { state ->
                    cachedState.set(state)
                    SubtitleTelemetryLogger.logState(state)
                    command.toEvent(previousMode, cachedSurfaceId.get())?.let { event ->
                        SubtitleTelemetryLogger.logFallback(event)
                    }
                }.onFailure { error ->
                    LogFacade.e(LogModule.PLAYER, TAG, "fallback failed: ${error.message}")
                }
            cachedState.get()
        }

    suspend fun submitTelemetry(sample: TelemetrySample) =
        withContext(dispatcher) {
            runCatching { api.submitTelemetry(sample) }
                .onFailure { error ->
                    LogFacade.w(LogModule.PLAYER, TAG, "submitTelemetry failed: ${error.message}")
                }
            cachedState.get()?.let { SubtitleTelemetryLogger.logSample(sample, it) }
        }

    suspend fun latestTelemetry() =
        withContext(dispatcher) {
            api.latestTelemetry()
        }

    fun currentState(): SubtitlePipelineState? = cachedState.get()

    fun telemetryEnabled(): Boolean = cachedState.get()?.telemetryEnabled ?: true

    fun surfaceId(): String? = cachedSurfaceId.get()

    fun reset() {
        cachedState.set(null)
        cachedSurfaceId.set(null)
    }

    companion object {
        private const val TAG = "SubtitlePipelineCtl"
    }
}
