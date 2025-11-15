package com.xyoye.player.subtitle.backend

import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.subtitle.MixedSubtitle

/**
 * Generic contract for subtitle rendering backends.
 */
interface SubtitleRenderer {
    val backend: SubtitleRendererBackend

    fun bind(environment: SubtitleRenderEnvironment)

    fun release()

    fun render(subtitle: MixedSubtitle)

    fun onSurfaceTypeChanged(surfaceType: SurfaceType)

    fun supportsExternalTrack(extension: String): Boolean

    fun loadExternalSubtitle(path: String): Boolean
}
