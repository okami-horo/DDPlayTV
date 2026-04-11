package com.xyoye.data_component.enums

/**
 * Reasons that can trigger GPU subtitle pipeline fallback.
 */
enum class SubtitlePipelineFallbackReason {
    GL_ERROR,
    SURFACE_LOST,
    UNSUPPORTED_GPU,
    INIT_TIMEOUT,
    SEEK,
    TRACK_CHANGED,
    TIMELINE_JUMP,
    UNKNOWN;

    companion object {
        fun fromReason(reason: String?): SubtitlePipelineFallbackReason = values().firstOrNull { it.name.equals(reason, true) } ?: UNKNOWN
    }
}
