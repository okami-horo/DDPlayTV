package com.xyoye.data_component.enums

/**
 * Rendering mode used by the subtitle pipeline.
 */
enum class SubtitlePipelineMode {
    GPU_GL,
    // Compatibility label for degraded sessions; it does not imply a CPU subtitle renderer is active.
    FALLBACK_CPU;

    companion object {
        fun fromName(value: String?): SubtitlePipelineMode = values().firstOrNull { it.name.equals(value, true) } ?: GPU_GL
    }
}
