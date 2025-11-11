package com.xyoye.player_component.media3.fallback

import com.xyoye.player_component.media3.mapper.LegacyCapabilityResult

sealed class CodecFallbackDecision {
    object None : CodecFallbackDecision()
    data class AudioOnly(val reason: String) : CodecFallbackDecision()
    data class BlockPlayback(val reason: String) : CodecFallbackDecision()
}

/**
 * Evaluates legacy capability results to determine Media3 fallback strategy.
 * Actual logic provided when tackling T019b.
 */
class CodecFallbackHandler {
    fun evaluate(result: LegacyCapabilityResult): CodecFallbackDecision {
        TODO("Not yet implemented")
    }
}
