package com.xyoye.player.subtitle.backend

import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.subtitle.MixedSubtitle

class CanvasTextRendererBackend : SubtitleRenderer {
    override val backend: SubtitleRendererBackend = SubtitleRendererBackend.LEGACY_CANVAS

    private var environment: SubtitleRenderEnvironment? = null

    override fun bind(environment: SubtitleRenderEnvironment) {
        this.environment = environment
    }

    override fun release() {
        environment = null
    }

    override fun render(subtitle: MixedSubtitle) {
        environment?.subtitleController?.onSubtitleTextOutput(subtitle)
    }

    override fun onSurfaceTypeChanged(surfaceType: SurfaceType) {
        // Legacy path is rendered through existing controller hierarchy.
    }

    override fun supportsExternalTrack(extension: String) = false

    override fun loadExternalSubtitle(path: String) = false
}
