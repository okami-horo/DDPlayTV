package com.xyoye.player.subtitle.gpu

import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode
import com.xyoye.data_component.enums.SubtitlePipelineStatus
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
    fun attemptRecovery(reason: SubtitlePipelineFallbackReason = SubtitlePipelineFallbackReason.UNKNOWN): Boolean {
        val state = pipelineController.currentState() ?: return false
        if (state.status != SubtitlePipelineStatus.Degraded || state.mode != SubtitlePipelineMode.FALLBACK_CPU) {
            return false
        }
        if (state.fallbackReason == SubtitlePipelineFallbackReason.UNSUPPORTED_GPU) {
            return false
        }
        val surface = tracker.currentSurface ?: return false
        val target = tracker.currentTarget ?: return false
        val surfaceId = tracker.surfaceId() ?: return false
        val telemetryEnabled = pipelineController.telemetryEnabled()
        scope.launch {
            fallbackController.markRecoveryPending(reason)
            renderer.bindSurface(
                surfaceId = surfaceId,
                surface = surface,
                target = target,
                telemetryEnabled = telemetryEnabled,
                recoveryTrigger = reason,
            )
        }
        return true
    }
}
