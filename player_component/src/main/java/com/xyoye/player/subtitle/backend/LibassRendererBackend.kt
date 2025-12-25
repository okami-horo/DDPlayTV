package com.xyoye.player.subtitle.backend

import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.player.kernel.subtitle.SubtitleKernelBridge
import com.xyoye.subtitle.MixedSubtitle

/**
 * GPU libass backend.
 *
 * - Embedded ASS/SSA is fed through the kernel bridge via [EmbeddedSubtitleSink].
 * - External ASS/SSA is loaded directly into the GPU renderer.
 * - Text/bitmap subtitles are still rendered by the legacy controller pipeline.
 */
@UnstableApi
class LibassRendererBackend(
    private val kernelBridge: SubtitleKernelBridge?
) : SubtitleRenderer {
    override val backend: SubtitleRendererBackend = SubtitleRendererBackend.LIBASS

    private var session: LibassGpuSubtitleSession? = null

    override fun bind(environment: SubtitleRenderEnvironment) {
        if (kernelBridge?.canStartGpuSubtitlePipeline() == false) {
            LogFacade.w(LogModule.PLAYER, TAG, "GPU subtitle pipeline not supported by kernel, skip bind")
            session = null
            return
        }
        session = LibassGpuSubtitleSession(environment, kernelBridge).also { it.start() }
    }

    override fun release() {
        session?.release()
        session = null
    }

    override fun render(subtitle: MixedSubtitle): Boolean {
        // libass pipeline does not consume MixedSubtitle events; keep legacy controller active.
        return false
    }

    override fun onSurfaceTypeChanged(surfaceType: SurfaceType) {
        // No-op: GPU pipeline manages its own overlay surface.
    }

    override fun supportsExternalTrack(extension: String): Boolean =
        session != null &&
            when (extension.lowercase()) {
                "ass", "ssa" -> true
                else -> false
            }

    override fun loadExternalSubtitle(path: String): Boolean {
        val current = session ?: return false
        LogFacade.i(LogModule.PLAYER, TAG, "GPU libass track loaded: $path")
        current.loadExternalTrack(path)
        return true
    }

    override fun updateOpacity(alphaPercent: Int) {
        session?.updateOpacity(alphaPercent)
    }

    override fun onSeek(positionMs: Long) {
        session?.onSeek(positionMs)
    }

    override fun onOffsetChanged(positionMs: Long) {
        session?.onOffsetChanged(positionMs)
    }

    companion object {
        private const val TAG = "LibassRendererBackend"
    }
}
