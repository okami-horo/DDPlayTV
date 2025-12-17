package com.xyoye.player_component.subtitle.gpu

import com.xyoye.common_component.log.SubtitleTelemetryLogger
import com.xyoye.data_component.bean.subtitle.TelemetrySample
import com.xyoye.data_component.enums.SubtitleFrameStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Collects render metrics coming back from the native GPU pipeline, applies
 * load-shedding hints, and streams telemetry to the pipeline controller.
 */
class SubtitleTelemetryCollector(
    private val pipelineController: SubtitlePipelineController,
    private val loadSheddingPolicy: SubtitleLoadSheddingPolicy = SubtitleLoadSheddingPolicy(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    fun allowRender(): Boolean = loadSheddingPolicy.allowRender()

    fun recordSkippedFrame(
        subtitlePtsMs: Long,
        _vsyncId: Long,
        reason: String = DROP_REASON_LOAD_SHED
    ) {
        val sample =
            TelemetrySample(
                timestampMs = System.currentTimeMillis(),
                subtitlePtsMs = subtitlePtsMs,
                renderLatencyMs = 0.0,
                uploadLatencyMs = 0.0,
                compositeLatencyMs = 0.0,
                frameStatus = SubtitleFrameStatus.Skipped,
                dropReason = reason,
                gpuOverutilized = true,
                vsyncMiss = true,
            )
        dispatch(sample, telemetryEnabled = !loadSheddingPolicy.allowRender())
    }

    fun recordRenderResult(
        result: AssGpuNativeBridge.NativeRenderResult,
        subtitlePtsMs: Long,
        _vsyncId: Long,
        telemetryEnabled: Boolean
    ) {
        val frameStatus =
            if (result.rendered) {
                SubtitleFrameStatus.Rendered
            } else {
                SubtitleFrameStatus.Dropped
            }
        val baseSample =
            TelemetrySample(
                timestampMs = System.currentTimeMillis(),
                subtitlePtsMs = subtitlePtsMs,
                renderLatencyMs = result.renderLatencyMs.toDouble(),
                uploadLatencyMs = result.uploadLatencyMs.toDouble(),
                compositeLatencyMs = result.compositeLatencyMs.toDouble(),
                frameStatus = frameStatus,
                dropReason = if (result.rendered) null else DROP_REASON_NO_FRAME,
            )
        val decision = loadSheddingPolicy.evaluateTelemetry(baseSample)
        val adjustedFrameStatus =
            when {
                decision.dropFrame -> SubtitleFrameStatus.Skipped
                else -> frameStatus
            }
        val adjustedSample =
            baseSample.copy(
                frameStatus = adjustedFrameStatus,
                dropReason =
                    when {
                        decision.dropFrame -> DROP_REASON_LOAD_SHED
                        baseSample.dropReason != null -> baseSample.dropReason
                        else -> null
                    },
                gpuOverutilized = decision.gpuOverutilized,
                vsyncMiss = decision.vsyncMiss,
            )
        dispatch(
            sample = adjustedSample,
            telemetryEnabled = telemetryEnabled && !decision.skipTelemetry,
        )
    }

    private fun dispatch(
        sample: TelemetrySample,
        telemetryEnabled: Boolean
    ) {
        val state = pipelineController.currentState()
        SubtitleTelemetryLogger.logSample(sample, state)
        if (!telemetryEnabled || !pipelineController.telemetryEnabled()) {
            return
        }
        scope.launch { pipelineController.submitTelemetry(sample) }
    }

    fun dispose() {
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val DROP_REASON_LOAD_SHED = "LOAD_SHED"
        private const val DROP_REASON_NO_FRAME = "NO_FRAME"
    }
}
