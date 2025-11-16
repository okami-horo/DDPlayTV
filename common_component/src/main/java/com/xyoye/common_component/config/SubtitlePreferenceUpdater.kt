package com.xyoye.common_component.config

import com.xyoye.common_component.enums.RendererPreferenceSource
import com.xyoye.common_component.enums.SubtitleRendererBackend

/**
 * Persists the renderer preference together with source + timestamp.
 */
object SubtitlePreferenceUpdater {
    fun persistBackend(backend: SubtitleRendererBackend, source: RendererPreferenceSource) {
        SubtitleConfig.putSubtitleRendererBackend(backend.name)
        SubtitleConfig.putSubtitleRendererSource(source.name)
        SubtitleConfig.putSubtitleRendererUpdatedAt(System.currentTimeMillis())
    }
}
