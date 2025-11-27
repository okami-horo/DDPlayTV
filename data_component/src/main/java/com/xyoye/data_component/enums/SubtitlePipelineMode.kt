package com.xyoye.data_component.enums

/**
 * Rendering mode used by the subtitle pipeline.
 */
enum class SubtitlePipelineMode {
    GPU_GL,
    Fallback_CPU;

    companion object {
        fun fromName(value: String?): SubtitlePipelineMode {
            return values().firstOrNull { it.name.equals(value, true) } ?: GPU_GL
        }
    }
}
