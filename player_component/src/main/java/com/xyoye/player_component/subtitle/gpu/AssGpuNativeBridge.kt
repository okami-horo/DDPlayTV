package com.xyoye.player_component.subtitle.gpu

import android.view.Surface
import com.xyoye.common_component.utils.DDLog
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

        private const val TAG = "AssGpuNativeBridge"
    }

    private var handle: Long = nativeCreate()

    val isReady: Boolean
        get() = handle != 0L

    fun attachSurface(surface: Surface?, target: SubtitleOutputTarget): Boolean {
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
            vsyncId
        )
    }

    fun detachSurface() {
        if (!isReady) return
        nativeDetachSurface(handle)
    }

    fun renderFrame(subtitlePtsMs: Long, vsyncId: Long, telemetryEnabled: Boolean = true): NativeRenderResult {
        if (!isReady) {
            return NativeRenderResult(rendered = false, renderLatencyMs = 0, uploadLatencyMs = 0, compositeLatencyMs = 0)
        }
        val raw = nativeRender(handle, subtitlePtsMs, vsyncId, telemetryEnabled)
        return decodeMetrics(raw)
    }

    fun setTelemetryEnabled(enabled: Boolean) {
        if (!isReady) return
        nativeSetTelemetryEnabled(handle, enabled)
    }

    fun loadTrack(path: String, fontDirs: List<String>, defaultFont: String?) {
        if (!isReady) return
        nativeLoadTrack(handle, path, fontDirs.toTypedArray(), defaultFont)
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

    private fun decodeMetrics(raw: LongArray?): NativeRenderResult {
        if (raw == null || raw.size < 4) {
            DDLog.w(TAG, "native metrics missing, using defaults")
            return NativeRenderResult(rendered = false, renderLatencyMs = 0, uploadLatencyMs = 0, compositeLatencyMs = 0)
        }
        return NativeRenderResult(
            rendered = raw[0] != 0L,
            renderLatencyMs = raw[1],
            uploadLatencyMs = raw[2],
            compositeLatencyMs = raw[3]
        )
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
        telemetryEnabled: Boolean
    ): LongArray?

    private external fun nativeFlush(handle: Long)

    private external fun nativeSetTelemetryEnabled(handle: Long, enabled: Boolean)

    private external fun nativeLoadTrack(
        handle: Long,
        path: String,
        fontDirs: Array<String>,
        defaultFont: String?
    )
}
