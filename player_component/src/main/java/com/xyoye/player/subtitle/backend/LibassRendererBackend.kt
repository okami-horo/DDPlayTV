package com.xyoye.player.subtitle.backend

import android.graphics.Bitmap
import android.view.Choreographer
import android.view.View
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.player.subtitle.libass.LibassBridge
import com.xyoye.player.subtitle.ui.SubtitleOverlayView
import com.xyoye.player.subtitle.ui.SubtitleSurfaceOverlay
import com.xyoye.subtitle.MixedSubtitle
import java.io.File
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.jvm.Volatile

class LibassRendererBackend : SubtitleRenderer {
    override val backend: SubtitleRendererBackend = SubtitleRendererBackend.LIBASS

    private var environment: SubtitleRenderEnvironment? = null
    private var bridge: LibassBridge? = null
    private var overlayView: SubtitleOverlayView? = null
    private var surfaceOverlay: SubtitleSurfaceOverlay? = null
    private var bitmap: Bitmap? = null
    private var currentSurfaceType: SurfaceType? = null
    @Volatile
    private var trackReady = false
    private var renderLoopRunning = false
    private var choreographer: Choreographer? = null
    private val frameCallback = Choreographer.FrameCallback {
        if (!renderLoopRunning) {
            return@FrameCallback
        }
        renderFrame()
        choreographer?.postFrameCallback(frameCallback)
    }

    override fun bind(environment: SubtitleRenderEnvironment) {
        this.environment = environment
        bridge = LibassBridge()
    }

    override fun release() {
        overlayView?.let { removeOverlay(it) }
        surfaceOverlay?.let { removeOverlay(it) }
        overlayView = null
        surfaceOverlay = null
        bitmap = null
        trackReady = false
        stopRenderLoop()
        bridge?.release()
        bridge = null
        environment = null
    }

    override fun render(subtitle: MixedSubtitle): Boolean {
        // libass handles rendering internally; MixedSubtitle pipeline is skipped.
        return false
    }

    override fun onSurfaceTypeChanged(surfaceType: SurfaceType) {
        currentSurfaceType = surfaceType
        when (surfaceType) {
            SurfaceType.VIEW_SURFACE -> ensureSurfaceOverlay()
            else -> ensureTextureOverlay()
        }
    }

    override fun supportsExternalTrack(extension: String): Boolean {
        return when (extension.lowercase(Locale.ROOT)) {
            "ass", "ssa" -> true
            else -> false
        }
    }

    override fun loadExternalSubtitle(path: String): Boolean {
        val loader = bridge ?: return false
        trackReady = false
        val success = loader.loadTrack(path)
        if (success) {
            loader.setFonts(null, buildFontDirectories(path))
            trackReady = true
            environment?.playerView?.post { startRenderLoop() }
        } else {
            environment?.playerView?.post { stopRenderLoop() }
        }
        return success
    }

    private fun ensureTextureOverlay() {
        if (overlayView != null) {
            return
        }
        val env = environment ?: return
        surfaceOverlay?.let { removeOverlay(it) }
        surfaceOverlay = null
        val overlay = SubtitleOverlayView(env.context).apply {
            setOnFrameSizeChanged { width, height -> updateFrameSize(width, height) }
        }
        env.playerView.attachSubtitleOverlay(overlay)
        overlayView = overlay
    }

    private fun ensureSurfaceOverlay() {
        if (surfaceOverlay != null) {
            return
        }
        val env = environment ?: return
        overlayView?.let { removeOverlay(it) }
        overlayView = null
        val overlay = SubtitleSurfaceOverlay(env.context).apply {
            setOnFrameSizeChanged { width, height -> updateFrameSize(width, height) }
        }
        env.playerView.attachSubtitleOverlay(overlay)
        surfaceOverlay = overlay
    }

    private fun removeOverlay(view: View) {
        environment?.playerView?.detachSubtitleOverlay(view)
    }

    private fun updateFrameSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        if (bitmap?.width != width || bitmap?.height != height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        bridge?.setFrameSize(width, height)
    }

    private fun buildFontDirectories(path: String): List<String> {
        val directories = LinkedHashSet<String>()
        val fileParent = File(path).parentFile
        if (fileParent != null && fileParent.exists()) {
            directories += fileParent.absolutePath
        }
        val systemCandidates = listOf(
            "/system/fonts",
            "/system/font",
            "/data/fonts"
        )
        systemCandidates.filter { File(it).exists() }.forEach { directories += it }
        return directories.toList()
    }

    private fun startRenderLoop() {
        if (renderLoopRunning) {
            return
        }
        renderLoopRunning = true
        if (choreographer == null) {
            choreographer = Choreographer.getInstance()
        }
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun stopRenderLoop() {
        if (!renderLoopRunning) {
            return
        }
        renderLoopRunning = false
        choreographer?.removeFrameCallback(frameCallback)
    }

    private fun renderFrame() {
        val env = environment ?: return
        val targetBitmap = bitmap ?: return
        val renderer = bridge ?: return
        if (!trackReady || !renderer.isReady()) {
            return
        }
        val position = env.playerView.getCurrentPosition()
        if (position < 0) {
            return
        }
        val changed = renderer.render(position, targetBitmap)
        if (!changed) {
            return
        }
        when (currentSurfaceType ?: SurfaceType.VIEW_TEXTURE) {
            SurfaceType.VIEW_SURFACE -> surfaceOverlay?.render(targetBitmap)
            SurfaceType.VIEW_TEXTURE -> overlayView?.render(targetBitmap)
        }
    }
}
