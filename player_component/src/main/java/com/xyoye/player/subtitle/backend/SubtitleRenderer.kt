package com.xyoye.player.subtitle.backend

import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.subtitle.MixedSubtitle
import androidx.media3.common.util.UnstableApi

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

    fun loadExternalSubtitle(path: String): Boolean

    fun updateOpacity(alphaPercent: Int) {
        // default no-op
    }
}
