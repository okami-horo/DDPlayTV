package com.xyoye.player.subtitle.gpu

import android.view.Surface
import com.xyoye.data_component.media3.telemetry.subtitle.SubtitleOutputTarget

class AssGpuNativeBridge {
    data class NativeRenderResult(
        val rendered: Boolean,
        val renderLatencyMs: Double,
        val uploadLatencyMs: Double,
        val compositeLatencyMs: Double,
        val submitted: Boolean = rendered,
        val imageCount: Int = -1,
        val packLatencyMs: Double = 0.0,
        val drawCalls: Int = 0,
        val textureBinds: Int = 0,
        val uploadedBytes: Long = 0,
        val textureBytes: Long = 0,
        val stagingBytes: Long = 0,
        val atlasPages: Int = 0,
        val glesVersion: Int = 0
    )

    data class EmbeddedTrackInfo(
        val eventCount: Int,
        val styleCount: Int,
        val hasEventFormat: Boolean
    )

    enum class EmbeddedSampleOutcome { UNAVAILABLE, ACCEPTED, EMPTY_TEXT, DUPLICATE, REJECTED, UNCHANGED }

    data class EmbeddedSampleDiagnostics(
        val track: EmbeddedTrackInfo,
        val addedEvents: Int,
        val textBytes: Int,
        val outcome: EmbeddedSampleOutcome
    )

    companion object {
        init {
            System.loadLibrary("libass_bridge")
        }
    }

    private var handle: Long = nativeCreate()
    private val metricsBuffer = LongArray(12)
    private val embeddedMetricsBuffer = LongArray(6)

    val isReady: Boolean
        get() = handle != 0L

    fun attachSurface(
        surface: Surface?,
        target: SubtitleOutputTarget
    ): Boolean {
        if (!isReady || surface?.isValid != true) return false
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
        contentTimeUs: Long,
        targetPresentTimeNs: Long?,
        telemetryEnabled: Boolean = true,
        stateRevision: Long = 0L
    ): NativeRenderResult {
        if (!isReady) {
            return NativeRenderResult(rendered = false, renderLatencyMs = 0.0, uploadLatencyMs = 0.0, compositeLatencyMs = 0.0)
        }
        metricsBuffer.fill(0)
        // Native telemetry can also be disabled independently of this request.
        metricsBuffer[3] = -1
        val status =
            nativeRender(handle, contentTimeUs, targetPresentTimeNs ?: 0L, stateRevision, metricsBuffer.takeIf { telemetryEnabled })
        return NativeRenderResult(
            rendered = status != 0,
            renderLatencyMs = metricsBuffer[0] / 1000.0,
            uploadLatencyMs = metricsBuffer[1] / 1000.0,
            compositeLatencyMs = metricsBuffer[2] / 1000.0,
            submitted = status == 1,
            imageCount = if (telemetryEnabled) metricsBuffer[3].toInt() else -1,
            packLatencyMs = metricsBuffer[4] / 1000.0,
            drawCalls = metricsBuffer[5].toInt(),
            textureBinds = metricsBuffer[6].toInt(),
            uploadedBytes = metricsBuffer[7],
            textureBytes = metricsBuffer[8],
            stagingBytes = metricsBuffer[9],
            atlasPages = metricsBuffer[10].toInt(),
            glesVersion = metricsBuffer[11].toInt()
        )
    }

    /** Selects a comparison path before attaching a surface; never changes production settings. */
    internal fun configureForTesting(
        atlasEnabled: Boolean,
        maxPageSize: Int = 0,
        glesVersion: Int = 0
    ): Boolean = isReady && nativeConfigureForTesting(handle, atlasEnabled, maxPageSize, glesVersion)

    fun setTelemetryEnabled(enabled: Boolean) {
        if (!isReady) return
        nativeSetTelemetryEnabled(handle, enabled)
    }

    @Synchronized
    fun invalidateState(revision: Long) {
        if (isReady) nativeInvalidateState(handle, revision)
    }

    fun resetMedia() {
        if (isReady) nativeResetMedia(handle)
    }

    fun addFont(
        name: String,
        data: ByteArray
    ): Boolean = isReady && nativeAddFont(handle, name, data)

    fun setVideoGeometry(
        width: Int,
        height: Int,
        pixelRatio: Float,
        rotationDegrees: Int
    ) {
        if (isReady) nativeSetVideoGeometry(handle, width, height, pixelRatio, rotationDegrees)
    }

    fun setGlobalOpacity(percent: Int) {
        if (!isReady) return
        nativeSetGlobalOpacity(handle, percent)
    }

    fun setFontScale(scale: Float) {
        if (!isReady) return
        nativeSetFontScale(handle, scale)
    }

    fun setVerticalOffsetPercent(percent: Int) {
        if (!isReady) return
        nativeSetVerticalOffsetPercent(handle, percent)
    }

    fun loadTrack(
        path: String,
        fontDirs: List<String>,
        defaultFont: String?
    ): Boolean = isReady && nativeLoadTrack(handle, path, fontDirs.toTypedArray(), defaultFont)

    fun initEmbeddedTrack(
        codecPrivate: ByteArray?,
        fontDirs: List<String>,
        defaultFont: String?
    ): Boolean = isReady && nativeInitEmbeddedTrack(handle, codecPrivate, fontDirs.toTypedArray(), defaultFont)

    fun readDocument(
        data: ByteArray,
        timeOffsetUs: Long,
        fontDirs: List<String>,
        defaultFont: String?
    ): Boolean = isReady && nativeReadDocument(handle, data, timeOffsetUs / 1000, fontDirs.toTypedArray(), defaultFont)

    fun embeddedTrackInfo(): EmbeddedTrackInfo? {
        if (!isReady) return null
        val info = nativeEmbeddedTrackInfo(handle) ?: return null
        return EmbeddedTrackInfo(info[0].toInt(), info[1].toInt(), info[2] != 0L)
    }

    fun appendEmbeddedChunk(
        data: ByteArray,
        timeMs: Long,
        durationMs: Long?,
        collectDiagnostics: Boolean = false
    ): EmbeddedSampleDiagnostics? {
        if (!isReady) return null
        val metrics = embeddedMetricsBuffer.takeIf { collectDiagnostics }
        metrics?.fill(0)
        nativeAppendEmbeddedChunk(handle, data, timeMs, durationMs ?: -1, metrics)
        if (metrics == null) return null
        return EmbeddedSampleDiagnostics(
            EmbeddedTrackInfo(metrics[0].toInt(), metrics[1].toInt(), metrics[5] != 0L),
            metrics[2].toInt(),
            metrics[3].toInt(),
            EmbeddedSampleOutcome.entries[metrics[4].toInt()]
        )
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

    @Synchronized
    fun release() {
        if (!isReady) return
        nativeDestroy(handle)
        handle = 0
    }

    private external fun nativeCreate(): Long

    private external fun nativeConfigureForTesting(
        handle: Long,
        atlasEnabled: Boolean,
        maxPageSize: Int,
        glesVersion: Int
    ): Boolean

    private external fun nativeReadDocument(
        handle: Long,
        data: ByteArray,
        timeOffsetMs: Long,
        fontDirs: Array<String>,
        defaultFont: String?
    ): Boolean

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
        contentTimeUs: Long,
        targetPresentTimeNs: Long,
        stateRevision: Long,
        metricsOut: LongArray?
    ): Int

    private external fun nativeInvalidateState(
        handle: Long,
        revision: Long
    )

    private external fun nativeResetMedia(handle: Long)

    private external fun nativeAddFont(
        handle: Long,
        name: String,
        data: ByteArray
    ): Boolean

    private external fun nativeSetVideoGeometry(
        handle: Long,
        width: Int,
        height: Int,
        pixelRatio: Float,
        rotationDegrees: Int
    )

    private external fun nativeFlush(handle: Long)

    private external fun nativeSetTelemetryEnabled(
        handle: Long,
        enabled: Boolean
    )

    private external fun nativeSetGlobalOpacity(
        handle: Long,
        percent: Int
    )

    private external fun nativeSetFontScale(
        handle: Long,
        scale: Float
    )

    private external fun nativeSetVerticalOffsetPercent(
        handle: Long,
        percent: Int
    )

    private external fun nativeLoadTrack(
        handle: Long,
        path: String,
        fontDirs: Array<String>,
        defaultFont: String?
    ): Boolean

    private external fun nativeInitEmbeddedTrack(
        handle: Long,
        codecPrivate: ByteArray?,
        fontDirs: Array<String>,
        defaultFont: String?
    ): Boolean

    private external fun nativeAppendEmbeddedChunk(
        handle: Long,
        data: ByteArray,
        timeMs: Long,
        durationMs: Long,
        diagnosticsOut: LongArray?
    )

    private external fun nativeEmbeddedTrackInfo(handle: Long): LongArray?

    private external fun nativeFlushEmbeddedEvents(handle: Long)

    private external fun nativeClearEmbeddedTrack(handle: Long)
}
