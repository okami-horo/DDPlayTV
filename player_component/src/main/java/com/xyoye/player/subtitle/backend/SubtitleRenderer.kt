package com.xyoye.player.subtitle.backend

import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.subtitle.MixedSubtitle

/**
 * Generic contract for subtitle rendering backends.
 */
@UnstableApi
interface SubtitleRenderer {
    val backend: SubtitleRendererBackend

    fun bind(environment: SubtitleRenderEnvironment)

    fun release()

    /**
     * @return true if the renderer took ownership of the subtitle event,
     * false to fall back to the legacy pipeline.
     */
    fun render(subtitle: MixedSubtitle): Boolean

    fun onSurfaceTypeChanged(surfaceType: SurfaceType)

    fun supportsExternalTrack(extension: String): Boolean

    suspend fun loadExternalSubtitle(path: String): Boolean

    fun setVisible(visible: Boolean) {
        // The legacy views are managed by SubtitleController.
    }

    /**
     * Clears any active external subtitle track owned by this renderer.
     */
    fun clearExternalSubtitle() {
        // default no-op
    }

    fun updateOpacity(alphaPercent: Int) {
        // default no-op
    }

    /**
     * Applies a global font scale offset for ASS/SSA rendering, based on the track's own style.
     */
    fun updateFontScaleOffset(offsetPercent: Int) {
        // default no-op
    }

    fun updateVerticalOffset(offsetPercent: Int) {
        // default no-op
    }

    /**
     * Notifies the renderer that the playback position has jumped (seek/discontinuity).
     */
    fun onSeek(positionMs: Long) {
        // default no-op
    }

    /**
     * Notifies the renderer that the subtitle clock offset changed while playback is active.
     */
    fun onOffsetChanged(positionMs: Long) {
        // default no-op
    }
}
