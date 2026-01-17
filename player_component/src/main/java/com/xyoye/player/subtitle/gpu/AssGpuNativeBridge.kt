package com.xyoye.player.subtitle.gpu

import android.view.Surface
import com.xyoye.data_component.bean.subtitle.SubtitleOutputTarget

class AssGpuNativeBridge {
    data class NativeRenderResult(
        val rendered: Boolean,
        val renderLatencyMs: Long,
        val uploadLatencyMs: Long,
        val compositeLatencyMs: Long
    )

    companion object {
        init {
            System.loadLibrary("libass_bridge")
        }
    }

    private var handle: Long = nativeCreate()
    private val metricsBuffer = LongArray(3)

    val isReady: Boolean
        get() = handle != 0L

    fun attachSurface(
        surface: Surface?,
        target: SubtitleOutputTarget
    ): Boolean {
        if (!isReady) return false
        val vsyncId = target.vsyncId ?: 0L
        return nativeAttachSurface(
            handle,
            surface,
            target.width,
            target.height,
            target.scale,
            target.rotation,
            target.colorFormat,
            target.supportsHardwareBuffer,
            vsyncId,
        )
    }

    fun detachSurface() {
        if (!isReady) return
        nativeDetachSurface(handle)
    }

    fun renderFrame(
        subtitlePtsMs: Long,
        vsyncId: Long,
        telemetryEnabled: Boolean = true
    ): NativeRenderResult {
        if (!isReady) {
            return NativeRenderResult(rendered = false, renderLatencyMs = 0, uploadLatencyMs = 0, compositeLatencyMs = 0)
        }
        return if (!telemetryEnabled) {
            val rendered = nativeRender(handle, subtitlePtsMs, vsyncId, null)
            NativeRenderResult(rendered = rendered, renderLatencyMs = 0, uploadLatencyMs = 0, compositeLatencyMs = 0)
        } else {
            metricsBuffer[0] = 0
            metricsBuffer[1] = 0
            metricsBuffer[2] = 0
            val rendered = nativeRender(handle, subtitlePtsMs, vsyncId, metricsBuffer)
            NativeRenderResult(
                rendered = rendered,
                renderLatencyMs = metricsBuffer[0],
                uploadLatencyMs = metricsBuffer[1],
                compositeLatencyMs = metricsBuffer[2],
            )
        }
    }

    fun setTelemetryEnabled(enabled: Boolean) {
        if (!isReady) return
        nativeSetTelemetryEnabled(handle, enabled)
    }

    fun setGlobalOpacity(percent: Int) {
        if (!isReady) return
        nativeSetGlobalOpacity(handle, percent)
    }

    fun loadTrack(
        path: String,
        fontDirs: List<String>,
        defaultFont: String?
    ) {
        if (!isReady) return
        nativeLoadTrack(handle, path, fontDirs.toTypedArray(), defaultFont)
    }

    fun initEmbeddedTrack(
        codecPrivate: ByteArray?,
        fontDirs: List<String>,
        defaultFont: String?
    ) {
        if (!isReady) return
        nativeInitEmbeddedTrack(handle, codecPrivate, fontDirs.toTypedArray(), defaultFont)
    }

    fun appendEmbeddedChunk(
        data: ByteArray,
        timeMs: Long,
        durationMs: Long?
    ) {
        if (!isReady) return
        nativeAppendEmbeddedChunk(handle, data, timeMs, durationMs ?: -1)
    }

    fun flushEmbeddedEvents() {
        if (!isReady) return
        nativeFlushEmbeddedEvents(handle)
    }

    fun clearEmbeddedTrack() {
        if (!isReady) return
        nativeClearEmbeddedTrack(handle)
    }

    fun flush() {
        if (!isReady) return
        nativeFlush(handle)
    }

    fun release() {
        if (!isReady) return
        nativeDestroy(handle)
        handle = 0
    }

    private external fun nativeCreate(): Long

    private external fun nativeDestroy(handle: Long)

    private external fun nativeAttachSurface(
        handle: Long,
        surface: Surface?,
        width: Int,
        height: Int,
        scale: Float,
        rotation: Int,
        colorFormat: String?,
        supportsHardwareBuffer: Boolean,
        vsyncId: Long
    ): Boolean

    private external fun nativeDetachSurface(handle: Long)

    private external fun nativeRender(
        handle: Long,
        subtitlePtsMs: Long,
        vsyncId: Long,
        metricsOut: LongArray?
    ): Boolean

    private external fun nativeFlush(handle: Long)

    private external fun nativeSetTelemetryEnabled(
        handle: Long,
        enabled: Boolean
    )

    private external fun nativeSetGlobalOpacity(
        handle: Long,
        percent: Int
    )

    private external fun nativeLoadTrack(
        handle: Long,
        path: String,
        fontDirs: Array<String>,
        defaultFont: String?
    )

    private external fun nativeInitEmbeddedTrack(
        handle: Long,
        codecPrivate: ByteArray?,
        fontDirs: Array<String>,
        defaultFont: String?
    )

    private external fun nativeAppendEmbeddedChunk(
        handle: Long,
        data: ByteArray,
        timeMs: Long,
        durationMs: Long
    )

    private external fun nativeFlushEmbeddedEvents(handle: Long)

    private external fun nativeClearEmbeddedTrack(handle: Long)
}
