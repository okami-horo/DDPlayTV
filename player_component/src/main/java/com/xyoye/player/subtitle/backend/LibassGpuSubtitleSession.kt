package com.xyoye.player.subtitle.backend

import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.common_component.subtitle.SubtitleFontManager
import com.xyoye.data_component.enums.SubtitleViewType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.subtitle.SubtitleFrameDriver
import com.xyoye.player.kernel.subtitle.SubtitleKernelBridge
import com.xyoye.player.subtitle.ui.SubtitleSurfaceOverlay
import com.xyoye.player_component.subtitle.gpu.AssGpuRenderer
import com.xyoye.player_component.subtitle.gpu.LocalSubtitlePipelineApi
import com.xyoye.player_component.subtitle.gpu.SubtitleFallbackController
import com.xyoye.player_component.subtitle.gpu.SubtitleOutputTargetTracker
import com.xyoye.player_component.subtitle.gpu.SubtitlePipelineController
import com.xyoye.player_component.subtitle.gpu.SubtitleRecoveryCoordinator
import com.xyoye.player_component.subtitle.gpu.SubtitleSurfaceLifecycleHandler

@UnstableApi
internal class LibassGpuSubtitleSession(
    private val environment: SubtitleRenderEnvironment,
    private val kernelBridge: SubtitleKernelBridge?,
) : SubtitleFrameDriver.Callback {
    private val pipelineApi = LocalSubtitlePipelineApi()
    private val pipelineController = SubtitlePipelineController(pipelineApi)
    private val gpuRenderer = AssGpuRenderer(pipelineController)
    private val fallbackController = SubtitleFallbackController(pipelineController)
    private val tracker = SubtitleOutputTargetTracker()
    private val lifecycleHandler = SubtitleSurfaceLifecycleHandler(gpuRenderer, tracker, fallbackController)
    private val recoveryCoordinator =
        SubtitleRecoveryCoordinator(gpuRenderer, tracker, fallbackController, pipelineController)

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
            runCatching { environment.playerView.detachSubtitleOverlay(view) }
        }
        overlay = null
        runCatching { gpuRenderer.release() }
    }

    fun loadExternalTrack(path: String) {
        val fonts = buildFontDirectories(environment.context)
        val defaultFont = SubtitleFontManager.getDefaultFontPath(environment.context)
        gpuRenderer.loadTrack(path, fonts, defaultFont)
        gpuRenderer.frameCleaner.onTrackChanged()
        renderOnceIfPaused(environment.playerView.getCurrentPosition())
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

    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!choreographerRunning) return
                if (environment.playerView.isPlaying() && tracker.currentTarget != null) {
                    val pts =
                        environment.playerView.getCurrentPosition().let { current ->
                            (current + SubtitlePreferenceUpdater.currentOffset()).coerceAtLeast(0L)
                        }
                    val vsyncId = frameTimeNanos / 1_000_000L
                    gpuRenderer.renderFrame(pts, vsyncId)
                }
                choreographer?.postFrameCallback(this)
            }
        }

    private fun attachOverlay() {
        val overlay =
            SubtitleSurfaceOverlay(environment.context).apply {
                bindPlayerView(environment.playerView)
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
        environment.playerView.attachSubtitleOverlay(overlay)
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
        if (!environment.playerView.isPlaying()) {
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
