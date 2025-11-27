package com.xyoye.data_component.enums

/**
 * Lifecycle state of the GPU subtitle pipeline.
 */
enum class SubtitlePipelineStatus {
    Initializing,
    Active,
    Degraded,
    Error;

    companion object {
        fun fromName(value: String?): SubtitlePipelineStatus {
            return values().firstOrNull { it.name.equals(value, true) } ?: Initializing
        }
    }
}
