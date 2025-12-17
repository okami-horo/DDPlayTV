package com.xyoye.data_component.enums

/**
 * Surface type descriptor used by the subtitle pipeline contracts.
 */
enum class SubtitleViewType {
    SurfaceView,
    TextureView;

    companion object {
        fun fromSurfaceType(surfaceType: SurfaceType): SubtitleViewType =
            when (surfaceType) {
                SurfaceType.VIEW_SURFACE -> SurfaceView
                else -> TextureView
            }
    }
}
