package com.xyoye.player.subtitle.backend

import com.xyoye.data_component.enums.SubtitleFallbackReason

/**
 * Dispatches fallback requests raised by subtitle renderers.
 */
fun interface SubtitleFallbackDispatcher {
    fun onSubtitleBackendFallback(reason: SubtitleFallbackReason, error: Throwable?)

    companion object {
        val NO_OP = SubtitleFallbackDispatcher { _, _ -> }
    }
}
