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
    @Volatile private var initializedBridge: AssGpuNativeBridge? = null
    private val nativeBridge: AssGpuNativeBridge by lazy(LazyThreadSafetyMode.NONE) {
        nativeBridgeFactory().also { bridge ->
            synchronized(frameLock) {
                bridge.invalidateState(stateRevision.get())
                initializedBridge = bridge
            }
        }
    }
    private val telemetryCollector =
        SubtitleTelemetryCollector(
            pipelineController = pipelineController,
            loadSheddingPolicy = loadSheddingPolicy,
            scope = scope,
        )
    val frameCleaner = SubtitleFrameCleaner { flush() }

    private val frameLock = Any()
    private var pendingFrame: SubtitleFrameRequest? = null
    private val stateRevision = AtomicLong(0L)
    private val surfaceRevision = AtomicLong(0L)
    private var surfaceAttached = false
    private var trackLoaded = false
    private var visible = true
    private var blockedByFailure = false
    private val embeddedSampleLogCounter = AtomicLong(0L)
    private var renderedFrameCount = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoPixelRatio = 1f
    private var videoRotation = 0

    @Volatile
    private var telemetryEnabled = true

    @Volatile
    private var initJob: Job? = null

    @Volatile
    private var released = false

    private val renderRunnable: Runnable =
        object : Runnable {
            override fun run() {
                val request = synchronized(frameLock) { pendingFrame.also { pendingFrame = null } }
                if (request != null && isCurrent(request)) renderOnRenderThread(request)
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
        postStateCommand {
            if (revision != surfaceRevision.get()) return@postStateCommand
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
                return@postStateCommand
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
        contentTimeUs: Long,
        targetPresentTimeNs: Long? = null
    ) {
        synchronized(frameLock) {
            if (released) return
            pendingFrame = SubtitleFrameRequest(contentTimeUs, targetPresentTimeNs, stateRevision.get(), surfaceRevision.get())
            renderHandler.removeCallbacks(renderRunnable)
            renderHandler.post(renderRunnable)
        }
    }

    private fun postStateCommand(action: () -> Unit): Boolean =
        synchronized(frameLock) {
            if (released) return@synchronized false
            invalidateFrames()
            renderHandler.post { if (!released) action() }
        }

    private fun invalidateFrames() {
        val revision = stateRevision.incrementAndGet()
        initializedBridge?.invalidateState(revision)
        pendingFrame = null
        renderHandler.removeCallbacks(renderRunnable)
    }

    private fun isCurrent(request: SubtitleFrameRequest): Boolean =
        !released && request.stateRevision == stateRevision.get() && request.surfaceRevision == surfaceRevision.get()

    fun resetMedia() {
        postStateCommand {
            trackLoaded = false
            nativeBridge.resetMedia()
        }
    }

    fun addFont(
        name: String,
        data: ByteArray
    ) {
        postStateCommand { nativeBridge.addFont(name, data) }
    }

    fun updateVideoGeometry(
        width: Int,
        height: Int,
        pixelRatio: Float,
        rotationDegrees: Int
    ) {
        if (width <= 0 || height <= 0) return
        synchronized(frameLock) {
            if (videoWidth == width && videoHeight == height && videoPixelRatio == pixelRatio && videoRotation == rotationDegrees) return
            videoWidth = width
            videoHeight = height
            videoPixelRatio = pixelRatio
            videoRotation = rotationDegrees
            postStateCommand { nativeBridge.setVideoGeometry(width, height, pixelRatio, rotationDegrees) }
        }
    }

    fun setVisible(visible: Boolean) {
        if (released) return
        postStateCommand {
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
        postStateCommand {
            nativeBridge.setGlobalOpacity(alphaPercent)
        }
    }

    fun updateFontScale(scale: Float) {
        if (released) return
        val clampedScale = scale.coerceIn(0.1f, 5f)
        postStateCommand {
            nativeBridge.setFontScale(clampedScale)
        }
    }

    fun updateVerticalOffset(offsetPercent: Int) {
        if (released) return
        postStateCommand {
            nativeBridge.setVerticalOffsetPercent(offsetPercent)
        }
    }

    fun detachSurface() {
        if (released) return
        surfaceRevision.incrementAndGet()
        synchronized(frameLock) { invalidateFrames() }
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
            if (!postStateCommand { task.run() }) continuation.resume(false)
            continuation.invokeOnCancellation { renderHandler.removeCallbacks(task) }
        }

    fun initEmbeddedTrack(
        codecPrivate: ByteArray?,
        fontDirs: List<String>,
        defaultFont: String?
    ) {
        if (released) return
        postStateCommand {
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
        postStateCommand {
            nativeBridge.flushEmbeddedEvents()
        }
    }

    fun clearEmbeddedTrack() {
        if (released) return
        postStateCommand {
            trackLoaded = false
            nativeBridge.clearEmbeddedTrack()
        }
    }

    fun flush() {
        if (released) return
        postStateCommand {
            nativeBridge.flush()
        }
    }

    fun release() {
        if (released) return
        synchronized(frameLock) {
            released = true
            invalidateFrames()
        }
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

    private fun renderOnRenderThread(request: SubtitleFrameRequest) {
        if (blockedByFailure || !surfaceAttached || !visible || !trackLoaded) return
        val state = pipelineController.currentState()
        if (state?.status == SubtitlePipelineStatus.Degraded) return

        if (!telemetryCollector.allowRender()) {
            telemetryCollector.recordSkippedFrame(request.contentTimeUs / 1000L, request.targetPresentTimeNs)
            return
        }
        if (!isCurrent(request)) return
        val renderStartedNs = System.nanoTime()
        val result = nativeBridge.renderFrame(request.contentTimeUs, request.targetPresentTimeNs, telemetryEnabled, request.stateRevision)
        if (!isCurrent(request)) return
        telemetryCollector.recordRenderResult(result, request.contentTimeUs / 1000L, request.targetPresentTimeNs, telemetryEnabled)
        renderedFrameCount++
        if (PlayerInitializer.isPrintLog && (renderedFrameCount <= 6 || renderedFrameCount % 50 == 0L)) {
            val queueUs = (renderStartedNs - request.requestedAtNs) / 1000L
            val targetDeltaUs = request.targetPresentTimeNs?.let { (System.nanoTime() - it) / 1000L }
            LogFacade.d(
                LogModule.PLAYER,
                TAG,
                "frame contentUs=${request.contentTimeUs} revision=${request.stateRevision} queueUs=$queueUs targetNs=${request.targetPresentTimeNs} targetDeltaUs=$targetDeltaUs presentation=immediate submitted=${result.submitted} cpuLayoutMs=${result.renderLatencyMs} cpuUploadMs=${result.uploadLatencyMs} cpuDrawSwapMs=${result.compositeLatencyMs}"
            )
        }

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
