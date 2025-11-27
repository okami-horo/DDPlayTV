package com.xyoye.player_component.subtitle.gpu

import android.view.Surface
import com.xyoye.common_component.log.SubtitleTelemetryLogger
import com.xyoye.common_component.utils.DDLog
import com.xyoye.data_component.bean.subtitle.SubtitleOutputTarget
import com.xyoye.data_component.bean.subtitle.TelemetrySample
import com.xyoye.data_component.enums.SubtitleFrameStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Orchestrates libass GPU rendering on the native render thread.
 */
class AssGpuRenderer(
    private val pipelineController: SubtitlePipelineController,
    private val nativeBridge: AssGpuNativeBridge = AssGpuNativeBridge(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    val frameCleaner = SubtitleFrameCleaner { nativeBridge.flush() }

    @Volatile
    private var telemetryEnabled = true

    @Volatile
    private var initJob: Job? = null

    fun bindSurface(
        surfaceId: String,
        surface: Surface?,
        target: SubtitleOutputTarget,
        telemetryEnabled: Boolean = true
    ) {
        this.telemetryEnabled = telemetryEnabled
        nativeBridge.attachSurface(surface, target)
        initJob?.cancel()
        initJob = scope.launch {
            runCatching { pipelineController.init(surfaceId, target, telemetryEnabled) }
                .onFailure { error ->
                    DDLog.e(TAG, "init pipeline failed: ${error.message}")
                }
        }
    }

    fun renderFrame(subtitlePtsMs: Long, vsyncId: Long) {
        val result = nativeBridge.renderFrame(subtitlePtsMs, vsyncId, telemetryEnabled)
        val frameStatus = if (result.rendered) {
            SubtitleFrameStatus.Rendered
        } else {
            SubtitleFrameStatus.Dropped
        }
        val sample = TelemetrySample(
            timestampMs = System.currentTimeMillis(),
            subtitlePtsMs = subtitlePtsMs,
            renderLatencyMs = result.renderLatencyMs.toDouble(),
            uploadLatencyMs = result.uploadLatencyMs.toDouble(),
            compositeLatencyMs = result.compositeLatencyMs.toDouble(),
            frameStatus = frameStatus,
            dropReason = if (result.rendered) null else DROP_REASON_NO_FRAME
        )
        pipelineController.currentState()?.let { SubtitleTelemetryLogger.logSample(sample, it) }
        if (pipelineController.telemetryEnabled()) {
            scope.launch { pipelineController.submitTelemetry(sample) }
        }
    }

    fun updateTelemetry(enabled: Boolean) {
        telemetryEnabled = enabled
        nativeBridge.setTelemetryEnabled(enabled)
    }

    fun detachSurface() {
        nativeBridge.detachSurface()
        frameCleaner.onSurfaceLost()
    }

    fun flush() {
        nativeBridge.flush()
    }

    fun release() {
        initJob?.cancel()
        flush()
        nativeBridge.release()
        pipelineController.reset()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val TAG = "AssGpuRenderer"
        private const val DROP_REASON_NO_FRAME = "NO_FRAME"
    }
}
