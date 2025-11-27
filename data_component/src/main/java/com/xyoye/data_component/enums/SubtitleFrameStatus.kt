package com.xyoye.data_component.enums

/**
 * Result of attempting to render a subtitle frame.
 */
enum class SubtitleFrameStatus {
    Rendered,
    Dropped,
    Skipped;

    companion object {
        fun fromName(value: String?): SubtitleFrameStatus {
            return values().firstOrNull { it.name.equals(value, true) } ?: Rendered
        }
    }
}
