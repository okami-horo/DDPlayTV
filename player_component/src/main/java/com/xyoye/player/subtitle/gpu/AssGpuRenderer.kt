package com.xyoye.player.subtitle.gpu

import android.os.Handler
import android.view.Surface
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.data_component.bean.subtitle.SubtitleOutputTarget
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode
import com.xyoye.data_component.enums.SubtitlePipelineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Orchestrates libass GPU rendering on the native render thread.
 */
class AssGpuRenderer(
    private val pipelineController: SubtitlePipelineController,
    private val renderHandler: Handler,
    private val loadSheddingPolicy: SubtitleLoadSheddingPolicy = SubtitleLoadSheddingPolicy(),
    private val scope: CoroutineScope,
    nativeBridgeFactory: () -> AssGpuNativeBridge = { AssGpuNativeBridge() },
    private val pipelineErrorListener: ((SubtitlePipelineFallbackReason, Throwable?) -> Unit)? = null
) {
    private val nativeBridge: AssGpuNativeBridge by lazy(LazyThreadSafetyMode.NONE) { nativeBridgeFactory() }
    private val telemetryCollector =
        SubtitleTelemetryCollector(
            pipelineController = pipelineController,
            loadSheddingPolicy = loadSheddingPolicy,
            scope = scope,
        )
    val frameCleaner = SubtitleFrameCleaner { flush() }

    private val renderScheduled = AtomicBoolean(false)
    private val pendingFrame = AtomicBoolean(false)
    private val pendingPtsMs = AtomicLong(0L)
    private val pendingVsyncId = AtomicLong(0L)
    private var trackLoaded = false
    private var blockedByFailure = false

    @Volatile
    private var telemetryEnabled = true

    @Volatile
    private var initJob: Job? = null

    @Volatile
    private var released = false

    private val renderRunnable: Runnable =
        object : Runnable {
            override fun run() {
                val shouldRender = pendingFrame.getAndSet(false)
                if (shouldRender && !released) {
                    renderOnRenderThread(
                        subtitlePtsMs = pendingPtsMs.get(),
                        vsyncId = pendingVsyncId.get(),
                    )
                }

                renderScheduled.set(false)
                if (pendingFrame.get() && renderScheduled.compareAndSet(false, true)) {
                    renderHandler.post(this)
                }
            }
        }

    fun bindSurface(
        surfaceId: String,
        surface: Surface?,
        target: SubtitleOutputTarget,
        telemetryEnabled: Boolean = true
    ) {
        if (released) return
        renderHandler.postAtFrontOfQueue {
            if (released) return@postAtFrontOfQueue
            blockedByFailure = false
            this.telemetryEnabled = telemetryEnabled
            if (!nativeBridge.attachSurface(surface, target)) {
                blockedByFailure = true
                pipelineErrorListener?.invoke(SubtitlePipelineFallbackReason.UNSUPPORTED_GPU, null)
            }
            initJob?.cancel()
            initJob =
                scope.launch {
                    runCatching { pipelineController.init(surfaceId, target, telemetryEnabled) }
                        .onFailure { error ->
                            LogFacade.e(LogModule.PLAYER, TAG, "init pipeline failed: ${error.message}")
                            pipelineErrorListener?.invoke(SubtitlePipelineFallbackReason.INIT_TIMEOUT, error)
                        }
                }
        }
    }

    fun renderFrame(
        subtitlePtsMs: Long,
        vsyncId: Long
    ) {
        if (released) return
        pendingPtsMs.set(subtitlePtsMs)
        pendingVsyncId.set(vsyncId)
        pendingFrame.set(true)
        if (renderScheduled.compareAndSet(false, true)) {
            renderHandler.post(renderRunnable)
        }
    }

    fun updateTelemetry(enabled: Boolean) {
        if (released) return
        renderHandler.postAtFrontOfQueue {
            if (released) return@postAtFrontOfQueue
            telemetryEnabled = enabled
            nativeBridge.setTelemetryEnabled(enabled)
        }
    }

    fun updateOpacity(alphaPercent: Int) {
        if (released) return
        renderHandler.postAtFrontOfQueue {
            if (released) return@postAtFrontOfQueue
            nativeBridge.setGlobalOpacity(alphaPercent)
        }
    }

    fun detachSurface() {
        if (released) return
        renderHandler.postAtFrontOfQueue {
            if (released) return@postAtFrontOfQueue
            nativeBridge.detachSurface()
        }
        frameCleaner.onSurfaceLost()
    }

    fun loadTrack(
        path: String,
        fontDirs: List<String>,
        defaultFont: String?
    ) {
        if (released) return
        renderHandler.postAtFrontOfQueue {
            if (released) return@postAtFrontOfQueue
            trackLoaded = true
            nativeBridge.loadTrack(path, fontDirs, defaultFont)
        }
    }

    fun initEmbeddedTrack(
        codecPrivate: ByteArray?,
        fontDirs: List<String>,
        defaultFont: String?
    ) {
        if (released) return
        renderHandler.post {
            if (released) return@post
            trackLoaded = true
            nativeBridge.initEmbeddedTrack(codecPrivate, fontDirs, defaultFont)
        }
    }

    fun appendEmbeddedSample(
        data: ByteArray,
        timeMs: Long,
        durationMs: Long?
    ) {
        if (released) return
        renderHandler.post {
            if (released) return@post
            nativeBridge.appendEmbeddedChunk(data, timeMs, durationMs)
        }
    }

    fun flushEmbeddedEvents() {
        if (released) return
        renderHandler.post {
            if (released) return@post
            nativeBridge.flushEmbeddedEvents()
        }
    }

    fun clearEmbeddedTrack() {
        if (released) return
        renderHandler.post {
            if (released) return@post
            trackLoaded = false
            nativeBridge.clearEmbeddedTrack()
        }
    }

    fun flush() {
        if (released) return
        renderHandler.postAtFrontOfQueue {
            if (released) return@postAtFrontOfQueue
            nativeBridge.flush()
        }
    }

    fun release() {
        if (released) return
        released = true

        renderHandler.removeCallbacksAndMessages(null)
        val latch = CountDownLatch(1)
        renderHandler.postAtFrontOfQueue {
            initJob?.cancel()
            runCatching { nativeBridge.flush() }
            runCatching { nativeBridge.release() }
            pipelineController.reset()
            latch.countDown()
        }
        if (!latch.await(1500, TimeUnit.MILLISECONDS)) {
            LogFacade.w(LogModule.PLAYER, TAG, "release timed out, render thread may be blocked")
        }
    }

    private fun renderOnRenderThread(
        subtitlePtsMs: Long,
        vsyncId: Long
    ) {
        if (blockedByFailure) return
        val state = pipelineController.currentState()
        if (state?.mode == SubtitlePipelineMode.FALLBACK_CPU) return

        if (!telemetryCollector.allowRender()) {
            telemetryCollector.recordSkippedFrame(subtitlePtsMs, vsyncId)
            return
        }
        val result = nativeBridge.renderFrame(subtitlePtsMs, vsyncId, telemetryEnabled)
        telemetryCollector.recordRenderResult(result, subtitlePtsMs, vsyncId, telemetryEnabled)

        if (!result.rendered && trackLoaded && state?.status == SubtitlePipelineStatus.Active) {
            blockedByFailure = true
            pipelineErrorListener?.invoke(SubtitlePipelineFallbackReason.GL_ERROR, null)
        }
    }

    companion object {
        private const val TAG = "AssGpuRenderer"
    }
}
