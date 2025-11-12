package com.xyoye.dandanplay.app.cast

import com.xyoye.data_component.entity.media3.CastTarget
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.player_component.media3.fallback.CodecFallbackDecision
import com.xyoye.player_component.media3.fallback.CodecFallbackHandler
import com.xyoye.player_component.media3.mapper.LegacyCapabilityResult

data class CastSessionPayload(
    val target: CastTarget,
    val sessionId: String,
    val audioOnly: Boolean,
    val fallbackMessage: String?
)

/**
 * Prepares metadata for Cast handoffs so PiP/background/capability state stays in sync with Media3.
 */
class Media3CastManager(
    private val codecFallbackHandler: CodecFallbackHandler
) {

    fun prepareCastSession(
        targetId: String,
        session: PlaybackSession,
        capability: PlayerCapabilityContract,
        capabilityResult: LegacyCapabilityResult?
    ): CastSessionPayload {
        val target = capability.castTargets.firstOrNull { it.id == targetId }
            ?: throw IllegalArgumentException("Cast target $targetId missing from capability contract")
        val decision = capabilityResult?.let { codecFallbackHandler.evaluate(it) }
            ?: CodecFallbackDecision.None
        val (audioOnly, message) = when (decision) {
            is CodecFallbackDecision.AudioOnly -> true to decision.reason
            is CodecFallbackDecision.BlockPlayback -> false to decision.reason
            CodecFallbackDecision.None -> false to null
        }
        return CastSessionPayload(
            target = target,
            sessionId = session.sessionId,
            audioOnly = audioOnly,
            fallbackMessage = message
        )
    }
}
