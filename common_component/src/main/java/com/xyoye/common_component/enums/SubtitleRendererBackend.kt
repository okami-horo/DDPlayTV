package com.xyoye.common_component.enums

/**
 * Subtitle rendering backend selection.
 */
enum class SubtitleRendererBackend {
    LEGACY_CANVAS,
    LIBASS;

    companion object {
        fun fromName(value: String?): SubtitleRendererBackend {
            return values().firstOrNull { it.name == value } ?: LEGACY_CANVAS
        }
    }
}
