package com.xyoye.player.subtitle.backend

import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.subtitle.SubtitleFontManager
import com.xyoye.common_component.utils.DDLog
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.data_component.enums.SubtitleViewType
import com.xyoye.player.subtitle.ui.SubtitleSurfaceOverlay
import com.xyoye.player_component.subtitle.gpu.AssGpuRenderer
import com.xyoye.player_component.subtitle.gpu.LocalSubtitlePipelineApi
import com.xyoye.player_component.subtitle.gpu.SubtitleFallbackController
import com.xyoye.player_component.subtitle.gpu.SubtitleOutputTargetTracker
import com.xyoye.player_component.subtitle.gpu.SubtitlePipelineController
import com.xyoye.player_component.subtitle.gpu.SubtitleRecoveryCoordinator
import com.xyoye.player_component.subtitle.gpu.SubtitleSurfaceLifecycleHandler
import com.xyoye.subtitle.MixedSubtitle

/**
 * GPU libass backend: routes rendering to the native GPU pipeline and discards
 * the old CPU bitmap overlay path.
 */
@UnstableApi
class LibassRendererBackend : SubtitleRenderer {
    override val backend: SubtitleRendererBackend = SubtitleRendererBackend.LIBASS

    private var environment: SubtitleRenderEnvironment? = null
    private var tracker: SubtitleOutputTargetTracker? = null
    private var pipelineController: SubtitlePipelineController? = null
    private var fallbackController: SubtitleFallbackController? = null
    private var renderer: AssGpuRenderer? = null
    private var lifecycleHandler: SubtitleSurfaceLifecycleHandler? = null
    private var recoveryCoordinator: SubtitleRecoveryCoordinator? = null
    private var overlay: SubtitleSurfaceOverlay? = null
    private var choreographer: Choreographer? = null
    private var renderLoopRunning = false

    override fun bind(environment: SubtitleRenderEnvironment) {
        this.environment = environment
        val api = LocalSubtitlePipelineApi()
        val controller = SubtitlePipelineController(api)
        val gpuRenderer = AssGpuRenderer(controller)
        val fallback = SubtitleFallbackController(controller)
        val targetTracker = SubtitleOutputTargetTracker()
        tracker = targetTracker
        pipelineController = controller
        fallbackController = fallback
        renderer = gpuRenderer
        lifecycleHandler = SubtitleSurfaceLifecycleHandler(gpuRenderer, targetTracker, fallback)
        recoveryCoordinator = SubtitleRecoveryCoordinator(gpuRenderer, targetTracker, fallback, controller)
        attachOverlay(environment)
        startRenderLoop()
    }

    override fun release() {
        stopRenderLoop()
        overlay?.let { ov ->
            environment?.playerView?.detachSubtitleOverlay(ov)
        }
        overlay = null
        renderer?.release()
        renderer = null
        lifecycleHandler = null
        recoveryCoordinator = null
        fallbackController = null
        tracker = null
        pipelineController = null
        environment = null
    }

    override fun render(subtitle: MixedSubtitle): Boolean {
        // GPU pipeline drives rendering via the frame loop; subtitle events are ignored here.
        return true
    }

    override fun onSurfaceTypeChanged(surfaceType: SurfaceType) {
        // No-op: GPU pipeline handles output via dedicated overlay surface.
    }

    override fun supportsExternalTrack(extension: String): Boolean {
        return when (extension.lowercase()) {
            "ass", "ssa" -> true
            else -> false
        }
    }

    override fun loadExternalSubtitle(path: String): Boolean {
        DDLog.i(TAG, "GPU libass backend loaded: $path")
        val env = environment ?: return false
        val fonts = buildFontDirectories(env.context)
        val defaultFont = SubtitleFontManager.getDefaultFontPath(env.context)
        renderer?.loadTrack(path, fonts, defaultFont)
        return true
    }

    override fun updateOpacity(alphaPercent: Int) {
        // GPU path manages opacity in native pipeline; ignore UI opacity tweaks.
    }

    private fun buildFontDirectories(context: android.content.Context): List<String> {
        SubtitleFontManager.ensureDefaultFont(context)
        return SubtitleFontManager.getFontsDirectoryPath(context)?.let { listOf(it) } ?: emptyList()
    }

    private fun attachOverlay(env: SubtitleRenderEnvironment) {
        val overlay = SubtitleSurfaceOverlay(env.context).apply {
            bindPlayerView(env.playerView)
            setOnFrameSizeChanged { width, height ->
                val surface = holder.surface
                if (surface != null && surface.isValid) {
                    lifecycleHandler?.onSurfaceSizeChanged(
                        surface = surface,
                        viewType = SubtitleViewType.SurfaceView,
                        width = width,
                        height = height,
                        rotation = 0,
                        telemetryEnabled = true
                    )
                }
            }
            setSurfaceStateListener(object : SubtitleSurfaceOverlay.SurfaceStateListener {
                override fun onSurfaceDestroyed() {
                    lifecycleHandler?.onSurfaceDestroyed()
                }

                override fun onSurfaceCreated(surface: Surface?, width: Int, height: Int) {
                    if (surface != null && surface.isValid) {
                        lifecycleHandler?.onSurfaceAvailable(
                            surface = surface,
                            viewType = SubtitleViewType.SurfaceView,
                            width = width,
                            height = height,
                            rotation = 0,
                            telemetryEnabled = true
                        )
                    }
                }

                override fun onSurfaceChanged(surface: Surface?, width: Int, height: Int) {
                    if (surface != null && surface.isValid) {
                        lifecycleHandler?.onSurfaceSizeChanged(
                            surface = surface,
                            viewType = SubtitleViewType.SurfaceView,
                            width = width,
                            height = height,
                            rotation = 0,
                            telemetryEnabled = true
                        )
                    }
                }
            })
        }
        env.playerView.attachSubtitleOverlay(overlay)
        this.overlay = overlay
    }

    private fun startRenderLoop() {
        if (renderLoopRunning) return
        renderLoopRunning = true
        if (choreographer == null) {
            choreographer = Choreographer.getInstance()
        }
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun stopRenderLoop() {
        if (!renderLoopRunning) return
        renderLoopRunning = false
        choreographer?.removeFrameCallback(frameCallback)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!renderLoopRunning) return
            val gpuRenderer = renderer
            val env = environment
            val targetTracker = tracker
            if (gpuRenderer != null && env != null && targetTracker?.currentTarget != null) {
                val pts = env.playerView.getCurrentPosition().let { current ->
                    (current + SubtitlePreferenceUpdater.currentOffset()).coerceAtLeast(0L)
                }
                val vsyncId = frameTimeNanos / 1_000_000L
                gpuRenderer.renderFrame(pts, vsyncId)
            }
            choreographer?.postFrameCallback(this)
        }
    }

    companion object {
        private const val TAG = "LibassRendererGPU"
    }
}
