package com.xyoye.player_component.subtitle.gpu

import android.view.Surface
import com.xyoye.data_component.bean.subtitle.SubtitleOutputTarget
import com.xyoye.data_component.enums.SubtitleViewType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks SurfaceView/TextureView geometry and exposes updates for pipeline init/status.
 */
class SubtitleOutputTargetTracker {
    private val targetState = MutableStateFlow<SubtitleOutputTarget?>(null)
    private val surfaceState = MutableStateFlow<Surface?>(null)
    private val surfaceIdCounter = AtomicLong(0L)
    @Volatile
    private var currentSurfaceId: String? = null

    val targets: Flow<SubtitleOutputTarget> = targetState.filterNotNull()

    val currentTarget: SubtitleOutputTarget?
        get() = targetState.value

    val currentSurface: Surface?
        get() = surfaceState.value

    fun updateSurface(
        surface: Surface?,
        viewType: SubtitleViewType,
        width: Int,
        height: Int,
        rotation: Int,
        scale: Float = 1f,
        colorFormat: String? = null,
        supportsHardwareBuffer: Boolean = false,
        vsyncId: Long? = null
    ): SubtitleOutputTarget {
        val target = SubtitleOutputTarget(
            viewType = viewType,
            width = width,
            height = height,
            scale = scale,
            rotation = rotation,
            colorFormat = colorFormat,
            supportsHardwareBuffer = supportsHardwareBuffer,
            vsyncId = vsyncId
        )
        targetState.value = target
        surfaceState.value = surface
        if (currentSurfaceId == null) {
            currentSurfaceId = buildSurfaceId()
        }
        return target
    }

    fun onSurfaceRecreated() {
        currentSurfaceId = buildSurfaceId()
    }

    fun onSurfaceDestroyed() {
        surfaceState.value = null
        targetState.value = null
        currentSurfaceId = null
    }

    fun surfaceId(): String? = currentSurfaceId

    private fun buildSurfaceId(): String {
        val next = surfaceIdCounter.incrementAndGet()
        return "surface-$next"
    }
}
