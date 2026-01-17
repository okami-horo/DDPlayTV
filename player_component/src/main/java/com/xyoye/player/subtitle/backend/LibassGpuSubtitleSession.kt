package com.xyoye.player.subtitle.backend

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.common_component.subtitle.SubtitleFontManager
import com.xyoye.data_component.enums.SubtitleFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode
import com.xyoye.data_component.enums.SubtitleViewType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.subtitle.SubtitleFrameDriver
import com.xyoye.player.kernel.subtitle.SubtitleKernelBridge
import com.xyoye.player.subtitle.ui.SubtitleSurfaceOverlay
import com.xyoye.player.subtitle.gpu.AssGpuRenderer
import com.xyoye.player.subtitle.gpu.LocalSubtitlePipelineApi
import com.xyoye.player.subtitle.gpu.SubtitleFallbackController
import com.xyoye.player.subtitle.gpu.SubtitleOutputTargetTracker
import com.xyoye.player.subtitle.gpu.SubtitlePipelineController
import com.xyoye.player.subtitle.gpu.SubtitleRecoveryCoordinator
import com.xyoye.player.subtitle.gpu.SubtitleSurfaceLifecycleHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
internal class LibassGpuSubtitleSession(
    private val environment: SubtitleRenderEnvironment,
    private val kernelBridge: SubtitleKernelBridge?
) : SubtitleFrameDriver.Callback {
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val renderThread = HandlerThread("LibassGpuSubtitleRender").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fallbackDispatched = AtomicBoolean(false)

    private val pipelineApi = LocalSubtitlePipelineApi()
    private val pipelineController = SubtitlePipelineController(pipelineApi)
    private val gpuRenderer =
        AssGpuRenderer(
            pipelineController = pipelineController,
            renderHandler = renderHandler,
            scope = sessionScope,
            pipelineErrorListener = ::onPipelineError,
        )
    private val fallbackController = SubtitleFallbackController(pipelineController)
    private val tracker = SubtitleOutputTargetTracker()
    private val lifecycleHandler =
        SubtitleSurfaceLifecycleHandler(
            renderer = gpuRenderer,
            tracker = tracker,
            fallbackController = fallbackController,
            scope = sessionScope,
        )
    private val recoveryCoordinator =
        SubtitleRecoveryCoordinator(
            renderer = gpuRenderer,
            tracker = tracker,
            fallbackController = fallbackController,
            pipelineController = pipelineController,
            scope = sessionScope,
        )

    private var overlay: SubtitleSurfaceOverlay? = null
    private var embeddedSink: EmbeddedSubtitleSink? = null
    private var frameDriver: SubtitleFrameDriver? = null

    private var choreographer: Choreographer? = null
    private var choreographerRunning = false

    fun start() {
        attachOverlay()
        gpuRenderer.updateOpacity(PlayerInitializer.Subtitle.alpha)
        registerEmbeddedSink()
        startFrameDriver()
    }

    fun release() {
        runCatching { stopFrameDriver() }
        runCatching { embeddedSink?.onRelease() }
        embeddedSink = null
        runCatching { kernelBridge?.setEmbeddedSubtitleSink(null) }
        overlay?.let { view ->
            environment.playerView?.let { playerView ->
                runCatching { playerView.detachSubtitleOverlay(view) }
            }
        }
        overlay = null
        runCatching { gpuRenderer.release() }
        runCatching { renderThread.quitSafely() }
        runCatching { renderThread.join(1500) }
        sessionScope.cancel()
        environment.clearUiReferences()
    }

    fun loadExternalTrack(path: String) {
        val fonts = buildFontDirectories(environment.context)
        val defaultFont = SubtitleFontManager.getDefaultFontPath(environment.context)
        gpuRenderer.loadTrack(path, fonts, defaultFont)
        gpuRenderer.frameCleaner.onTrackChanged()
        val positionMs = environment.playerView?.getCurrentPosition() ?: 0L
        renderOnceIfPaused(positionMs)
    }

    fun updateOpacity(alphaPercent: Int) {
        gpuRenderer.updateOpacity(alphaPercent)
    }

    fun onSeek(positionMs: Long) {
        gpuRenderer.frameCleaner.onSeek()
        renderOnceIfPaused(positionMs)
    }

    fun onOffsetChanged(positionMs: Long) {
        renderOnceIfPaused(positionMs)
    }

    override fun onVideoFrame(
        videoPtsMs: Long,
        vsyncId: Long
    ) {
        if (tracker.currentTarget == null) return
        val pts =
            (videoPtsMs + SubtitlePreferenceUpdater.currentOffset())
                .coerceAtLeast(0L)
        gpuRenderer.renderFrame(pts, vsyncId)
    }

    override fun onTimelineJump(
        positionMs: Long,
        playing: Boolean,
        reason: SubtitleFrameDriver.TimelineJumpReason
    ) {
        when (reason) {
            SubtitleFrameDriver.TimelineJumpReason.TRACKS_CHANGED -> gpuRenderer.frameCleaner.onTrackChanged()
            else -> gpuRenderer.frameCleaner.onSeek()
        }
        if (!playing) {
            renderOnce(positionMs)
        }
    }

    override fun onPlaybackEnded() {
        gpuRenderer.frameCleaner.onTrackChanged()
    }

    private fun startFrameDriver() {
        val driver = kernelBridge?.createFrameDriver(this)
        if (driver != null) {
            frameDriver = driver
            driver.start()
            return
        }
        startChoreographerLoop()
    }

    private fun stopFrameDriver() {
        frameDriver?.stop()
        frameDriver = null
        stopChoreographerLoop()
    }

    private fun startChoreographerLoop() {
        if (choreographerRunning) return
        choreographerRunning = true
        if (choreographer == null) {
            choreographer = Choreographer.getInstance()
        }
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun stopChoreographerLoop() {
        if (!choreographerRunning) return
        choreographerRunning = false
        choreographer?.removeFrameCallback(frameCallback)
    }

    private fun onPipelineError(
        reason: SubtitlePipelineFallbackReason,
        error: Throwable?
    ) {
        sessionScope.launch {
            if (fallbackController.currentState()?.mode == SubtitlePipelineMode.FALLBACK_CPU) {
                return@launch
            }
            fallbackController.forceFallback(reason)
        }

        val backendReason =
            when (reason) {
                SubtitlePipelineFallbackReason.SURFACE_LOST -> null
                SubtitlePipelineFallbackReason.UNSUPPORTED_GPU,
                SubtitlePipelineFallbackReason.INIT_TIMEOUT -> SubtitleFallbackReason.INIT_FAIL
                SubtitlePipelineFallbackReason.GL_ERROR,
                SubtitlePipelineFallbackReason.UNKNOWN -> SubtitleFallbackReason.RENDER_FAIL
            } ?: return

        if (!fallbackDispatched.compareAndSet(false, true)) {
            return
        }
        val dispatcher = environment.fallbackDispatcher ?: return
        mainHandler.post { dispatcher.onSubtitleBackendFallback(backendReason, error) }
    }

    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!choreographerRunning) return
                val playerView =
                    environment.playerView ?: run {
                        stopChoreographerLoop()
                        return
                    }
                if (playerView.isPlaying() && tracker.currentTarget != null) {
                    val pts =
                        playerView.getCurrentPosition().let { current ->
                            (current + SubtitlePreferenceUpdater.currentOffset()).coerceAtLeast(0L)
                        }
                    val vsyncId = frameTimeNanos / 1_000_000L
                    gpuRenderer.renderFrame(pts, vsyncId)
                }
                choreographer?.postFrameCallback(this)
            }
        }

    private fun attachOverlay() {
        val playerView = environment.playerView ?: return
        val overlay =
            SubtitleSurfaceOverlay(environment.context).apply {
                bindPlayerView(playerView)
                setOnFrameSizeChanged { width, height ->
                    val surface = holder.surface
                    if (surface != null && surface.isValid) {
                        lifecycleHandler.onSurfaceSizeChanged(
                            surface = surface,
                            viewType = SubtitleViewType.SurfaceView,
                            width = width,
                            height = height,
                            rotation = 0,
                            telemetryEnabled = true,
                        )
                    }
                }
                setSurfaceStateListener(
                    object : SubtitleSurfaceOverlay.SurfaceStateListener {
                        override fun onSurfaceDestroyed() {
                            lifecycleHandler.onSurfaceDestroyed()
                        }

                        override fun onSurfaceCreated(
                            surface: Surface?,
                            width: Int,
                            height: Int
                        ) {
                            if (surface != null && surface.isValid) {
                                lifecycleHandler.onSurfaceAvailable(
                                    surface = surface,
                                    viewType = SubtitleViewType.SurfaceView,
                                    width = width,
                                    height = height,
                                    rotation = 0,
                                    telemetryEnabled = true,
                                )
                            }
                        }

                        override fun onSurfaceChanged(
                            surface: Surface?,
                            width: Int,
                            height: Int
                        ) {
                            if (surface != null && surface.isValid) {
                                lifecycleHandler.onSurfaceSizeChanged(
                                    surface = surface,
                                    viewType = SubtitleViewType.SurfaceView,
                                    width = width,
                                    height = height,
                                    rotation = 0,
                                    telemetryEnabled = true,
                                )
                            }
                        }
                    },
                )
            }
        playerView.attachSubtitleOverlay(overlay)
        this.overlay = overlay
    }

    private fun registerEmbeddedSink() {
        val fonts = buildFontDirectories(environment.context)
        val defaultFont = SubtitleFontManager.getDefaultFontPath(environment.context)
        val sink = LibassEmbeddedSubtitleSink(gpuRenderer, fonts, defaultFont)
        embeddedSink = sink
        kernelBridge?.setEmbeddedSubtitleSink(sink)
    }

    private fun buildFontDirectories(context: android.content.Context): List<String> {
        SubtitleFontManager.ensureDefaultFont(context)
        return SubtitleFontManager.getFontsDirectoryPath(context)?.let { listOf(it) } ?: emptyList()
    }

    private fun renderOnceIfPaused(positionMs: Long) {
        val playerView = environment.playerView
        if (playerView == null || !playerView.isPlaying()) {
            renderOnce(positionMs)
        }
    }

    private fun renderOnce(positionMs: Long) {
        if (tracker.currentTarget == null) return
        val pts =
            (positionMs + SubtitlePreferenceUpdater.currentOffset())
                .coerceAtLeast(0L)
        val vsyncId = SystemClock.elapsedRealtimeNanos() / 1_000_000L
        gpuRenderer.renderFrame(pts, vsyncId)
    }
}
