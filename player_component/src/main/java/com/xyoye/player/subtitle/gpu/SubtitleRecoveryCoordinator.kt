package com.xyoye.player.subtitle.gpu

import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Tries to bring the GPU pipeline back online after a fallback once conditions
 * (surface/context) are healthy again.
 */
class SubtitleRecoveryCoordinator(
    private val renderer: AssGpuRenderer,
    private val tracker: SubtitleOutputTargetTracker,
    private val fallbackController: SubtitleFallbackController,
    private val pipelineController: SubtitlePipelineController,
    private val scope: CoroutineScope
) {
    fun attemptRecovery(reason: SubtitlePipelineFallbackReason = SubtitlePipelineFallbackReason.UNKNOWN) {
        scope.launch {
            val state = pipelineController.currentState()
            if (state?.mode != SubtitlePipelineMode.FALLBACK_CPU) {
                return@launch
            }
            val surface = tracker.currentSurface ?: return@launch
            val target = tracker.currentTarget ?: return@launch
            val surfaceId = tracker.surfaceId() ?: return@launch
            fallbackController.resumeGpu(reason)
            renderer.bindSurface(surfaceId, surface, target, pipelineController.telemetryEnabled())
        }
    }
}
