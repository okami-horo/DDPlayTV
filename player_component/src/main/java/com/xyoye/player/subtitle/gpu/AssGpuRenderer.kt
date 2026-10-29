package com.xyoye.player.subtitle.gpu

import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineStatus
import com.xyoye.data_component.media3.telemetry.subtitle.SubtitleOutputTarget
import com.xyoye.player.info.PlayerInitializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Orchestrates libass GPU rendering on the native render thread.
 */
class AssGpuRenderer(
    private val pipelineController: SubtitlePipelineController,
    private val renderHandler: Handler,
    private val loadSheddingPolicy: SubtitleLoadSheddingPolicy = SubtitleLoadSheddingPolicy(),
    private val scope: CoroutineScope,
    nativeBridgeFactory: () -> AssGpuNativeBridge = { AssGpuNativeBridge() },
    private val pipelineErrorListener: ((SubtitlePipelineFallbackReason, Throwable?) -> Unit)? = null,
    private val pipelineRecoveryListener: ((SubtitlePipelineFallbackReason, Boolean) -> Unit)? = null,
    private val surfaceReadyListener: (() -> Unit)? = null
) {
    private val nativeBridge: AssGpuNativeBridge by lazy(LazyThreadSafetyMode.NONE) { nativeBridgeFactory() }
    private val telemetryCollector =
        SubtitleTelemetryCollector(
            pipelineController = pipelineController,
            loadSheddingPolicy = loadSheddingPolicy,
            scope = scope,
        )
    val frameCleaner = SubtitleFrameCleaner { flush() }

    private val frameLock = Any()
    private val pendingPtsMs = AtomicLong(0L)
    private val pendingVsyncId = AtomicLong(0L)
    private val surfaceRevision = AtomicLong(0L)
    private var surfaceAttached = false
    private var trackLoaded = false
    private var visible = true
    private var blockedByFailure = false
    private val embeddedSampleLogCounter = AtomicLong(0L)

    @Volatile
    private var telemetryEnabled = true

    @Volatile
    private var initJob: Job? = null

    @Volatile
    private var released = false

    private val renderRunnable: Runnable =
        object : Runnable {
            override fun run() {
                if (!released) {
                    renderOnRenderThread(
                        subtitlePtsMs = pendingPtsMs.get(),
                        vsyncId = pendingVsyncId.get(),
                    )
                }
            }
        }

    fun bindSurface(
        surfaceId: String,
        surface: Surface?,
        target: SubtitleOutputTarget,
        telemetryEnabled: Boolean = true,
        recoveryTrigger: SubtitlePipelineFallbackReason? = null
    ) {
        if (released) return
        val revision = surfaceRevision.incrementAndGet()
        renderHandler.post {
            if (released || revision != surfaceRevision.get()) return@post
            initJob?.cancel()
            surfaceAttached = false
            blockedByFailure = false
            this.telemetryEnabled = telemetryEnabled
            if (!nativeBridge.attachSurface(surface, target)) {
                blockedByFailure = true
                recoveryTrigger?.let { trigger ->
                    pipelineRecoveryListener?.invoke(trigger, false)
                }
                val reason =
                    if (surface?.isValid == true) {
                        SubtitlePipelineFallbackReason.GL_ERROR
                    } else {
                        SubtitlePipelineFallbackReason.SURFACE_LOST
                    }
                pipelineErrorListener?.invoke(reason, null)
                return@post
            }
            surfaceAttached = true
            initJob =
                scope.launch {
                    runCatching { pipelineController.init(surfaceId, target, telemetryEnabled) }
                        .onSuccess {
                            surfaceReadyListener?.invoke()
                            recoveryTrigger?.let { trigger ->
                                pipelineRecoveryListener?.invoke(trigger, true)
                            }
                        }.onFailure { error ->
                            if (error is CancellationException) return@onFailure
                            LogFacade.e(LogModule.PLAYER, TAG, "init pipeline failed: ${error.message}")
                            recoveryTrigger?.let { trigger ->
                                pipelineRecoveryListener?.invoke(trigger, false)
                            }
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
        // Move a queued frame behind state/track commands already submitted to this looper.
        // This matters when a paused redraw races with hide/seek/track changes.
        synchronized(frameLock) {
            renderHandler.removeCallbacks(renderRunnable)
            renderHandler.post(renderRunnable)
        }
    }

    fun setVisible(visible: Boolean) {
        if (released) return
        renderHandler.post {
            if (released) return@post
            this.visible = visible
            if (!visible) nativeBridge.flush()
        }
    }

    fun updateTelemetry(enabled: Boolean) {
        if (released) return
        renderHandler.post {
            if (released) return@post
            telemetryEnabled = enabled
            nativeBridge.setTelemetryEnabled(enabled)
        }
    }

    fun updateOpacity(alphaPercent: Int) {
        if (released) return
        renderHandler.post {
            if (released) return@post
            nativeBridge.setGlobalOpacity(alphaPercent)
        }
    }

    fun updateFontScale(scale: Float) {
        if (released) return
        val clampedScale = scale.coerceIn(0.1f, 5f)
        renderHandler.post {
            if (released) return@post
            nativeBridge.setFontScale(clampedScale)
        }
    }

    fun updateVerticalOffset(offsetPercent: Int) {
        if (released) return
        renderHandler.post {
            if (released) return@post
            nativeBridge.setVerticalOffsetPercent(offsetPercent)
        }
    }

    fun detachSurface() {
        if (released) return
        surfaceRevision.incrementAndGet()
        val detach = {
            initJob?.cancel()
            surfaceAttached = false
            nativeBridge.detachSurface()
        }
        if (Looper.myLooper() == renderHandler.looper) {
            detach()
            return
        }
        // SurfaceHolder requires rendering to stop before surfaceDestroyed returns.
        val detached = CountDownLatch(1)
        if (renderHandler.post {
                try {
                    if (!released) detach()
                } finally {
                    detached.countDown()
                }
            } &&
            !detached.await(1500, TimeUnit.MILLISECONDS)
        ) {
            LogFacade.w(LogModule.PLAYER, TAG, "surface detach timed out")
        }
    }

    suspend fun loadTrack(
        path: String,
        fontDirs: List<String>,
        defaultFont: String?
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            if (released) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            val task =
                Runnable {
                    if (!continuation.isActive) return@Runnable
                    val loaded = !released && nativeBridge.loadTrack(path, fontDirs, defaultFont)
                    trackLoaded = loaded
                    continuation.resume(loaded)
                }
            if (!renderHandler.post(task)) continuation.resume(false)
            continuation.invokeOnCancellation { renderHandler.removeCallbacks(task) }
        }

    fun initEmbeddedTrack(
        codecPrivate: ByteArray?,
        fontDirs: List<String>,
        defaultFont: String?
    ) {
        if (released) return
        renderHandler.post {
            if (released) return@post
            trackLoaded = nativeBridge.initEmbeddedTrack(codecPrivate, fontDirs, defaultFont)
            if (PlayerInitializer.isPrintLog) {
                LogFacade.d(
                    LogModule.PLAYER,
                    TAG,
                    "gpu initEmbeddedTrack codecPrivateSize=${codecPrivate?.size ?: -1} fontDirs=${fontDirs.size} defaultFontSet=${defaultFont != null}",
                )
            }
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
            val count = embeddedSampleLogCounter.incrementAndGet()
            if (PlayerInitializer.isPrintLog && shouldLogEmbeddedSample(count)) {
                LogFacade.d(
                    LogModule.PLAYER,
                    TAG,
                    "gpu appendEmbeddedSample size=${data.size} ptsMs=$timeMs durationMs=${durationMs ?: -1}",
                )
            }
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
        renderHandler.post {
            if (released) return@post
            nativeBridge.flush()
        }
    }

    fun release() {
        if (released) return
        released = true
        surfaceRevision.incrementAndGet()

        renderHandler.removeCallbacks(renderRunnable)
        val latch = CountDownLatch(1)
        renderHandler.post {
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
        if (blockedByFailure || !surfaceAttached || !visible || !trackLoaded) return
        val state = pipelineController.currentState()
        if (state?.status == SubtitlePipelineStatus.Degraded) return

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
        private const val SAMPLE_LOG_LIMIT = 6L
        private const val SAMPLE_LOG_INTERVAL = 50L
    }

    private fun shouldLogEmbeddedSample(count: Long): Boolean = count <= SAMPLE_LOG_LIMIT || count % SAMPLE_LOG_INTERVAL == 0L
}
