package com.xyoye.player.subtitle.backend

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.utils.DDLog
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.enums.SubtitleFallbackReason
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.subtitle.debug.PlaybackSessionStatusProvider
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
    @Volatile
    private var fallbackDispatched = false
    private var trackLoadedAtMs: Long? = null
    private var firstRenderLogged = false
    private val frameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!renderLoopRunning) {
                return
            }
            renderFrame()
            choreographer?.postFrameCallback(this)
        }
    }

    override fun bind(environment: SubtitleRenderEnvironment) {
        this.environment = environment
        bridge = LibassBridge()
        fallbackDispatched = false
        trackLoadedAtMs = null
        firstRenderLogged = false
        PlaybackSessionStatusProvider.startSession(
            backend,
            currentSurfaceType ?: SurfaceType.VIEW_TEXTURE,
            bitmap?.width ?: 0,
            bitmap?.height ?: 0
        )
    }

    override fun release() {
        overlayView?.let { removeOverlay(it) }
        surfaceOverlay?.let { removeOverlay(it) }
        overlayView = null
        surfaceOverlay = null
        bitmap = null
        trackReady = false
        PlaybackSessionStatusProvider.endSession()
        stopRenderLoop()
        bridge?.release()
        bridge = null
        environment = null
        fallbackDispatched = false
    }

    override fun render(subtitle: MixedSubtitle): Boolean {
        // libass handles rendering internally; MixedSubtitle pipeline is skipped.
        return false
    }

    override fun onSurfaceTypeChanged(surfaceType: SurfaceType) {
        currentSurfaceType = surfaceType
        PlaybackSessionStatusProvider.updateSurface(surfaceType)
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
        fallbackDispatched = false
        trackLoadedAtMs = SystemClock.elapsedRealtime()
        val result = try {
            loader.loadTrack(path)
        } catch (e: Throwable) {
            DDLog.e("LIBASS-Error", "libass loadTrack crash: ${e.message}")
            handleFatalError(
                SubtitleFallbackReason.INIT_FAIL,
                e,
                "loadTrack failed for $path"
            )
            false
        }
        val success = result
        if (success) {
            loader.setFonts(null, buildFontDirectories(path))
            trackReady = true
            PlaybackSessionStatusProvider.startSession(
                backend,
                currentSurfaceType ?: SurfaceType.VIEW_TEXTURE,
                bitmap?.width ?: 0,
                bitmap?.height ?: 0
            )
            environment?.playerView?.post { startRenderLoop() }
        } else {
            handleFatalError(
                SubtitleFallbackReason.INIT_FAIL,
                null,
                "loadTrack returned false for $path"
            )
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
            bindPlayerView(env.playerView)
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
            bindPlayerView(env.playerView)
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
        PlaybackSessionStatusProvider.updateFrameSize(width, height)
    }

    private fun buildFontDirectories(path: String): List<String> {
        val directories = LinkedHashSet<String>()
        PlayerInitializer.selectSourceDirectory?.let { sourceDirPath ->
            val sourceDir = File(sourceDirPath)
            if (sourceDir.exists() && sourceDir.isDirectory) {
                directories += sourceDir.absolutePath
                val subDirNames = listOf("Fonts", "fonts", "font")
                subDirNames
                    .map { File(sourceDir, it) }
                    .filter { it.exists() && it.isDirectory }
                    .forEach { directories += it.absolutePath }
            }
        }
        val systemCandidates = listOf(
            "/system/fonts",
            "/system/font",
            "/data/fonts"
        )
        systemCandidates.filter { File(it).exists() }.forEach { directories += it }
        if (directories.isNotEmpty()) {
            DDLog.i(
                "LIBASS-Debug",
                "subtitle font search dirs=${directories.joinToString(prefix = "[", postfix = "]")}"
            )
        }
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
        if (fallbackDispatched) {
            return
        }
        if (!trackReady || !renderer.isReady()) {
            return
        }
        val position = env.playerView.getCurrentPosition()
        if (position < 0) {
            return
        }
        val offsetPosition = PlayerInitializer.Subtitle.offsetPosition
        val renderPosition = (position + offsetPosition).coerceAtLeast(0L)
        val changed = try {
            renderer.render(renderPosition, targetBitmap)
        } catch (e: Throwable) {
            DDLog.e("LIBASS-Error", "libass render failed: ${e.message}")
            handleFatalError(
                SubtitleFallbackReason.RENDER_FAIL,
                e,
                "render failed at position=$renderPosition"
            )
            false
        }
        if (!changed) {
            return
        }
        onFirstFrameRendered()
        val verticalOffsetPx = calculateVerticalOffset(targetBitmap.height)
        when (currentSurfaceType ?: SurfaceType.VIEW_TEXTURE) {
            SurfaceType.VIEW_SURFACE -> surfaceOverlay?.render(targetBitmap, verticalOffsetPx)
            SurfaceType.VIEW_TEXTURE -> overlayView?.render(targetBitmap, verticalOffsetPx)
        }
    }

    private fun calculateVerticalOffset(targetHeight: Int): Int {
        val offsetPercent = PlayerInitializer.Subtitle.verticalOffset
        if (offsetPercent == 0 || targetHeight <= 0) {
            return 0
        }
        val offsetPx = (targetHeight * (offsetPercent / 100f)).toInt()
        return offsetPx.coerceIn(-targetHeight, targetHeight)
    }

    private fun onFirstFrameRendered() {
        if (firstRenderLogged.not()) {
            val start = trackLoadedAtMs
            if (start != null) {
                val latency = SystemClock.elapsedRealtime() - start
                val baseline = LEGACY_LATENCY_BASE_MS
                val ratio = latency.toDouble() / baseline.toDouble()
                DDLog.i(
                    "LIBASS-Perf",
                    "first subtitle latency=${latency}ms (baseline=${baseline}ms, ratio=${"%.2f".format(ratio)})"
                )
            }
            PlaybackSessionStatusProvider.markFirstRender()
            firstRenderLogged = true
        }
    }

    private fun handleFatalError(
        reason: SubtitleFallbackReason,
        error: Throwable?,
        message: String
    ) {
        if (fallbackDispatched) {
            return
        }
        fallbackDispatched = true
        trackReady = false
        ErrorReportHelper.postException(
            "libass backend failure: $message",
            "SubtitleFallback-$reason",
            error
        )
        DDLog.e(
            "LIBASS-Error",
            "Dispatching subtitle fallback. reason=$reason, message=$message"
        )
        PlaybackSessionStatusProvider.markFallback(reason, error)
        val env = environment ?: return
        env.playerView.post {
            stopRenderLoop()
            env.fallbackDispatcher.onSubtitleBackendFallback(reason, error)
        }
    }

    companion object {
        private const val LEGACY_LATENCY_BASE_MS = 120L
    }
}
