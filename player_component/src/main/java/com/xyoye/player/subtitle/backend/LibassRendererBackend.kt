package com.xyoye.player.subtitle.backend

import android.graphics.Bitmap
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

class LibassRendererBackend : SubtitleRenderer {
    override val backend: SubtitleRendererBackend = SubtitleRendererBackend.LIBASS

    private var environment: SubtitleRenderEnvironment? = null
    private var bridge: LibassBridge? = null
    private var overlayView: SubtitleOverlayView? = null
    private var surfaceOverlay: SubtitleSurfaceOverlay? = null
    private var bitmap: Bitmap? = null
    private var currentSurfaceType: SurfaceType? = null

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
        bridge?.release()
        bridge = null
        environment = null
    }

    override fun render(subtitle: MixedSubtitle) {
        // libass handles rendering internally; MixedSubtitle pipeline is skipped.
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
        val success = loader.loadTrack(path)
        if (success) {
            loader.setFonts(null, buildFontDirectories(path))
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
}
