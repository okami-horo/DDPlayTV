package com.xyoye.player_component.subtitle.gpu

import android.view.Surface
import com.xyoye.common_component.utils.DDLog
import com.xyoye.data_component.bean.subtitle.SubtitleOutputTarget
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
    private val loadSheddingPolicy: SubtitleLoadSheddingPolicy = SubtitleLoadSheddingPolicy(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val telemetryCollector = SubtitleTelemetryCollector(
        pipelineController = pipelineController,
        loadSheddingPolicy = loadSheddingPolicy,
        scope = scope
    )
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
        if (!telemetryCollector.allowRender()) {
            telemetryCollector.recordSkippedFrame(subtitlePtsMs, vsyncId)
            return
        }
        val result = nativeBridge.renderFrame(subtitlePtsMs, vsyncId, telemetryEnabled)
        telemetryCollector.recordRenderResult(result, subtitlePtsMs, vsyncId, telemetryEnabled)
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
        telemetryCollector.dispose()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val TAG = "AssGpuRenderer"
    }
}
