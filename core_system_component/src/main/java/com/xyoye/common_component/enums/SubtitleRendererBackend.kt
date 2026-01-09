package com.xyoye.common_component.enums

/**
 * Subtitle rendering backend selection.
 */
enum class SubtitleRendererBackend {
    LEGACY_CANVAS,
    LIBASS;

    companion object {
        fun fromName(value: String?): SubtitleRendererBackend {
            val backend = values().firstOrNull { it.name == value } ?: LIBASS
            // Legacy backend is disabled; always normalize to libass.
            return if (backend == LEGACY_CANVAS) LIBASS else backend
        }
    }
}
