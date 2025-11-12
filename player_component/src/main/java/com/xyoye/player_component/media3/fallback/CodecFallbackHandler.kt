package com.xyoye.player_component.media3.fallback

import com.xyoye.player_component.media3.mapper.LegacyCapabilityResult

sealed class CodecFallbackDecision {
    object None : CodecFallbackDecision()
    data class AudioOnly(val reason: String) : CodecFallbackDecision()
    data class BlockPlayback(val reason: String) : CodecFallbackDecision()
}

/**
 * Evaluates legacy capability results to determine Media3 fallback strategy.
 * - No blocking issues => proceed with normal playback.
 * - Blocking codec issues => switch to audio-only while surfacing reason.
 * - Other blocking issues (e.g., DRM) => block playback entirely.
 */
class CodecFallbackHandler {

    fun evaluate(result: LegacyCapabilityResult): CodecFallbackDecision {
        if (!result.hasBlockingIssue) {
            return CodecFallbackDecision.None
        }

        val codecIssues = result.blockingIssues.filter { it.code == CODEC_UNSUPPORTED }
        if (codecIssues.isNotEmpty()) {
            val message = codecIssues.joinToString("; ") { it.message }
            return CodecFallbackDecision.AudioOnly(message)
        }

        val reason = result.blockingIssues.joinToString("; ") { it.message }
        return CodecFallbackDecision.BlockPlayback(reason.ifEmpty { "Media3 blocking issue detected" })
    }

    private companion object {
        const val CODEC_UNSUPPORTED = "UNSUPPORTED_CODEC"
    }
}
