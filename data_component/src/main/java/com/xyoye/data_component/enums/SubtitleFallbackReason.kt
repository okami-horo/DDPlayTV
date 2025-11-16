package com.xyoye.data_component.enums

/**
 * Reasons that can trigger a subtitle backend fallback.
 */
enum class SubtitleFallbackReason {
    INIT_FAIL,
    RENDER_FAIL,
    UNSUPPORTED_FORMAT,
    USER_REQUEST
}

