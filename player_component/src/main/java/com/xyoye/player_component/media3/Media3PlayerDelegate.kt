package com.xyoye.player_component.media3

import com.xyoye.common_component.config.Media3ToggleProvider
import com.xyoye.data_component.data.media3.CapabilityCommandResponseData
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3SourceType
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import com.xyoye.player_component.media3.session.Media3SessionController

/**
 * Media3 player delegate orchestrates session lifecycle, capability dispatching,
 * and startup telemetry. Implementation provided in T015.
 */
class Media3PlayerDelegate(
    private val sessionController: Media3SessionController,
    private val toggleResolver: () -> RolloutToggleSnapshot = { Media3ToggleProvider.snapshot() },
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun prepareSession(
        mediaId: String,
        sourceType: Media3SourceType,
        requestedCapabilities: List<Media3Capability> = emptyList(),
        autoplay: Boolean = true
    ): Result<PlayerCapabilityContract> {
        TODO("Not yet implemented")
    }

    suspend fun dispatchCapability(
        capability: Media3Capability,
        payload: Map<String, Any?>? = null
    ): Result<CapabilityCommandResponseData> {
        TODO("Not yet implemented")
    }

    fun currentSession(): PlaybackSession? = TODO("Not yet implemented")

    fun currentCapability(): PlayerCapabilityContract? = TODO("Not yet implemented")

    fun rolloutSnapshot(): RolloutToggleSnapshot? = TODO("Not yet implemented")

    fun startupLatencyMs(): Long? = TODO("Not yet implemented")

    fun isStartupWithinTarget(targetMs: Long = STARTUP_BUDGET_MS): Boolean {
        TODO("Not yet implemented")
    }

    fun markFirstFrame() {
        TODO("Not yet implemented")
    }

    companion object {
        const val STARTUP_BUDGET_MS = 2_000L
    }
}
