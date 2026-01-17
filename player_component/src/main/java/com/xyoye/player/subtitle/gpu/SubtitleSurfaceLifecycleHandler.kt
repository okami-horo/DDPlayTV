package com.xyoye.player.subtitle.gpu

import android.view.Surface
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitleViewType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Coordinates surface lifecycle events with the GPU renderer and pipeline
 * controller so re-creates and resizes do not leave stale GL state behind.
 */
class SubtitleSurfaceLifecycleHandler(
    private val renderer: AssGpuRenderer,
    private val tracker: SubtitleOutputTargetTracker,
    private val fallbackController: SubtitleFallbackController? = null,
    private val scope: CoroutineScope
) {
    fun onSurfaceAvailable(
        surface: Surface?,
        viewType: SubtitleViewType,
        width: Int,
        height: Int,
        rotation: Int,
        scale: Float = 1f,
        colorFormat: String? = null,
        supportsHardwareBuffer: Boolean = false,
        vsyncId: Long? = null,
        telemetryEnabled: Boolean = true
    ) {
        tracker.onSurfaceRecreated()
        val target =
            tracker.updateSurface(
                surface = surface,
                viewType = viewType,
                width = width,
                height = height,
                rotation = rotation,
                scale = scale,
                colorFormat = colorFormat,
                supportsHardwareBuffer = supportsHardwareBuffer,
                vsyncId = vsyncId,
            )
        val surfaceId = tracker.surfaceId() ?: buildFallbackId()
        renderer.bindSurface(surfaceId, surface, target, telemetryEnabled)
        fallbackController?.let { controller ->
            scope.launch { controller.resumeGpu(SubtitlePipelineFallbackReason.SURFACE_LOST) }
        }
    }

    fun onSurfaceSizeChanged(
        surface: Surface?,
        viewType: SubtitleViewType,
        width: Int,
        height: Int,
        rotation: Int,
        scale: Float = 1f,
        colorFormat: String? = null,
        supportsHardwareBuffer: Boolean = false,
        vsyncId: Long? = null,
        telemetryEnabled: Boolean = true
    ) {
        val target =
            tracker.updateSurface(
                surface = surface,
                viewType = viewType,
                width = width,
                height = height,
                rotation = rotation,
                scale = scale,
                colorFormat = colorFormat,
                supportsHardwareBuffer = supportsHardwareBuffer,
                vsyncId = vsyncId,
            )
        tracker.surfaceId()?.let {
            renderer.bindSurface(it, surface, target, telemetryEnabled)
        }
    }

    fun onSurfaceDestroyed() {
        if (tracker.surfaceId() != null) {
            fallbackController?.let { controller ->
                scope.launch { controller.forceFallback(SubtitlePipelineFallbackReason.SURFACE_LOST) }
            }
        }
        tracker.onSurfaceDestroyed()
        renderer.detachSurface()
    }

    private fun buildFallbackId(): String = "surface-${System.currentTimeMillis()}"
}
