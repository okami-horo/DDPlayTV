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
import com.xyoye.data_component.enums.SubtitlePipelineStatus
import com.xyoye.data_component.enums.SubtitleViewType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.subtitle.SubtitleFrameDriver
import com.xyoye.player.kernel.subtitle.SubtitleKernelBridge
import com.xyoye.player.subtitle.gpu.AssGpuRenderer
import com.xyoye.player.subtitle.gpu.LocalSubtitlePipelineApi
import com.xyoye.player.subtitle.gpu.SubtitleFallbackController
import com.xyoye.player.subtitle.gpu.SubtitleOutputTargetTracker
import com.xyoye.player.subtitle.gpu.SubtitlePipelineController
import com.xyoye.player.subtitle.gpu.SubtitleRecoveryCoordinator
import com.xyoye.player.subtitle.gpu.SubtitleSurfaceLifecycleHandler
import com.xyoye.player.subtitle.ui.SubtitleSurfaceOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            pipelineRecoveryListener = ::onRecoveryResult,
            surfaceReadyListener = {
                onMainThread { renderOnceIfPaused(environment.playerView?.getCurrentPosition() ?: 0L) }
            },
        )
    private val fallbackController = SubtitleFallbackController(pipelineController)
    private val tracker = SubtitleOutputTargetTracker()
    private val recoveryCoordinator =
        SubtitleRecoveryCoordinator(
            renderer = gpuRenderer,
            tracker = tracker,
            fallbackController = fallbackController,
            pipelineController = pipelineController,
            scope = sessionScope,
        )
    private val lifecycleHandler =
        SubtitleSurfaceLifecycleHandler(
            renderer = gpuRenderer,
            tracker = tracker,
            fallbackController = fallbackController,
            recoveryCoordinator = recoveryCoordinator,
            scope = sessionScope,
        )

    private var overlay: SubtitleSurfaceOverlay? = null
    private var embeddedSink: EmbeddedSubtitleSink? = null
    private var embeddedSinkController: EmbeddedSubtitleSinkController? = null
    private var frameDriver: SubtitleFrameDriver? = null

    private var choreographer: Choreographer? = null
    private var choreographerRunning = false
    private var externalTrackRevision = 0L
    private var released = false

    fun start() {
        attachOverlay()
        setVisible(environment.subtitleController?.isUserSubtitleVisible() ?: true)
        gpuRenderer.updateOpacity(PlayerInitializer.Subtitle.alpha)
        gpuRenderer.updateFontScale(offsetPercentToScale(PlayerInitializer.Subtitle.fontScaleOffsetPercent))
        gpuRenderer.updateVerticalOffset(PlayerInitializer.Subtitle.verticalOffset)
        registerEmbeddedSink()
        startFrameDriver()
    }

    fun release() {
        released = true
        externalTrackRevision++
        runCatching { stopFrameDriver() }
        runCatching { embeddedSink?.onRelease() }
        embeddedSink = null
        embeddedSinkController = null
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

    suspend fun loadExternalTrack(path: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (released) return@withContext false
            val revision = ++externalTrackRevision
            embeddedSinkController?.setEnabled(false)
            val fonts = buildFontDirectories(environment.context)
            val defaultFont = SubtitleFontManager.getDefaultFontPath(environment.context)
            val loaded =
                try {
                    gpuRenderer.loadTrack(path, fonts, defaultFont)
                } catch (cancelled: CancellationException) {
                    if (!released && externalTrackRevision == revision) clearActiveTrack()
                    throw cancelled
                }
            if (released || externalTrackRevision != revision) return@withContext false
            if (!loaded) {
                clearActiveTrack()
                return@withContext false
            }
            recoveryCoordinator.attemptRecovery(SubtitlePipelineFallbackReason.TRACK_CHANGED)
            renderOnceIfPaused(environment.playerView?.getCurrentPosition() ?: 0L)
            true
        }

    fun setVisible(visible: Boolean) {
        onMainThread {
            gpuRenderer.setVisible(visible)
            if (visible) renderOnce(environment.playerView?.getCurrentPosition() ?: 0L)
        }
    }

    fun clearActiveTrack() {
        onMainThread { clearActiveTrackOnMainThread() }
    }

    private fun clearActiveTrackOnMainThread() {
        externalTrackRevision++
        gpuRenderer.clearEmbeddedTrack()
        // External subtitle removed: allow embedded sink again and restore embedded-track mode.
        embeddedSinkController?.setEnabled(true)
        // Re-register the sink to replay cached embedded ASS data when supported (e.g. mpv bridge).
        kernelBridge?.setEmbeddedSubtitleSink(embeddedSink)
        recoveryCoordinator.attemptRecovery(SubtitlePipelineFallbackReason.TRACK_CHANGED)
        val positionMs = environment.playerView?.getCurrentPosition() ?: 0L
        renderOnceIfPaused(positionMs)
    }

    fun updateOpacity(alphaPercent: Int) {
        gpuRenderer.updateOpacity(alphaPercent)
        onMainThread { renderOnceIfPaused(environment.playerView?.getCurrentPosition() ?: 0L) }
    }

    fun updateFontScaleOffset(offsetPercent: Int) {
        gpuRenderer.updateFontScale(offsetPercentToScale(offsetPercent))
        val positionMs = environment.playerView?.getCurrentPosition() ?: 0L
        renderOnceIfPaused(positionMs)
    }

    fun updateVerticalOffset(offsetPercent: Int) {
        gpuRenderer.updateVerticalOffset(offsetPercent)
        val positionMs = environment.playerView?.getCurrentPosition() ?: 0L
        renderOnceIfPaused(positionMs)
    }

    fun onSeek(positionMs: Long) {
        gpuRenderer.frameCleaner.onSeek()
        recoveryCoordinator.attemptRecovery(SubtitlePipelineFallbackReason.SEEK)
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
        val recoveryTrigger =
            when (reason) {
                SubtitleFrameDriver.TimelineJumpReason.SEEK -> SubtitlePipelineFallbackReason.SEEK
                SubtitleFrameDriver.TimelineJumpReason.TRACKS_CHANGED -> SubtitlePipelineFallbackReason.TRACK_CHANGED
                SubtitleFrameDriver.TimelineJumpReason.POSITION_DISCONTINUITY -> SubtitlePipelineFallbackReason.TIMELINE_JUMP
            }
        recoveryCoordinator.attemptRecovery(recoveryTrigger)
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
            if (fallbackController.currentState()?.status == SubtitlePipelineStatus.Degraded) {
                return@launch
            }
            fallbackController.enterDegraded(reason)
        }

        val backendReason =
            when (reason) {
                SubtitlePipelineFallbackReason.SURFACE_LOST -> null
                SubtitlePipelineFallbackReason.UNSUPPORTED_GPU,
                SubtitlePipelineFallbackReason.INIT_TIMEOUT -> SubtitleFallbackReason.INIT_FAIL
                SubtitlePipelineFallbackReason.GL_ERROR,
                SubtitlePipelineFallbackReason.UNKNOWN,
                SubtitlePipelineFallbackReason.SEEK,
                SubtitlePipelineFallbackReason.TRACK_CHANGED,
                SubtitlePipelineFallbackReason.TIMELINE_JUMP -> SubtitleFallbackReason.RENDER_FAIL
            } ?: return

        if (!fallbackDispatched.compareAndSet(false, true)) {
            return
        }
        val dispatcher = environment.fallbackDispatcher ?: return
        mainHandler.post { dispatcher.onSubtitleBackendFallback(backendReason, error) }
    }

    private fun onRecoveryResult(
        reason: SubtitlePipelineFallbackReason,
        succeeded: Boolean
    ) {
        fallbackController.reportRecoveryOutcome(reason = reason, succeeded = succeeded)
        if (!succeeded) {
            return
        }
        // Recovery arrives on the render/IO thread; player reads belong to the UI thread.
        // The session scope cancels queued work when this subtitle session is released.
        sessionScope.launch(Dispatchers.Main) {
            fallbackDispatched.set(false)
            val positionMs = environment.playerView?.getCurrentPosition() ?: return@launch
            renderOnceIfPaused(positionMs)
        }
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
        val sink =
            SwitchableEmbeddedSubtitleSink(
                LibassEmbeddedSubtitleSink(gpuRenderer, fonts, defaultFont) {
                    onMainThread { renderOnceIfPaused(environment.playerView?.getCurrentPosition() ?: 0L) }
                },
            )
        embeddedSink = sink
        embeddedSinkController = sink
        kernelBridge?.setEmbeddedSubtitleSink(sink)
    }

    private fun buildFontDirectories(context: android.content.Context): List<String> {
        SubtitleFontManager.ensureDefaultFont(context)
        return SubtitleFontManager.getFontsDirectoryPath(context)?.let { listOf(it) } ?: emptyList()
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!released) action()
        } else {
            mainHandler.post { if (!released) action() }
        }
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

    private fun offsetPercentToScale(offsetPercent: Int): Float {
        val clampedOffset = offsetPercent.coerceIn(FONT_SCALE_OFFSET_MIN, FONT_SCALE_OFFSET_MAX)
        val scale = 1f + clampedOffset / 100f
        return scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    }

    private companion object {
        private const val FONT_SCALE_OFFSET_MIN = -50
        private const val FONT_SCALE_OFFSET_MAX = 400
        private const val MIN_FONT_SCALE = 0.1f
        private const val MAX_FONT_SCALE = 5f
    }
}
