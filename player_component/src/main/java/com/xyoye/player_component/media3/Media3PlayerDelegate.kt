package com.xyoye.player_component.media3

import com.xyoye.common_component.config.Media3ToggleProvider
import com.xyoye.common_component.network.repository.Media3SessionBundle
import com.xyoye.data_component.data.media3.CapabilityCommandResponseData
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3SourceType
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import com.xyoye.player_component.media3.session.Media3SessionController
import com.xyoye.player_component.media3.session.RolloutSnapshotManager
import kotlin.jvm.JvmSuppressWildcards

/**
 * Wraps Media3 session orchestration so feature modules interact with a simple delegate.
 * Handles rollout toggle evaluation, capability dispatch, and startup telemetry bookkeeping.
 */
class Media3PlayerDelegate(
    private val sessionController: Media3SessionController,
    private val snapshotManager: RolloutSnapshotManager = RolloutSnapshotManager { Media3ToggleProvider.snapshot() },
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    private var activeBundle: Media3SessionBundle? = null
    private var sessionStartAt: Long? = null
    private var firstFrameAt: Long? = null

    suspend fun prepareSession(
        mediaId: String,
        sourceType: Media3SourceType,
        requestedCapabilities: List<Media3Capability> = emptyList(),
        autoplay: Boolean = true
    ): Result<PlayerCapabilityContract> {
        val snapshot = snapshotManager.evaluate()
        if (!snapshot.value) {
            return Result.failure(IllegalStateException("Media3 is disabled by rollout snapshot"))
        }

        val startAt = timeProvider()
        val result = sessionController.prepareSession(
            mediaId = mediaId,
            sourceType = sourceType,
            requestedCapabilities = requestedCapabilities,
            autoplay = autoplay
        )

        return result
            .onSuccess { bundle ->
                sessionStartAt = startAt
                firstFrameAt = null
                activeBundle = bundle
                snapshotManager.bind(bundle.session.sessionId, snapshot)
            }
            .map { it.capabilityContract }
    }

    suspend fun refreshSession(): Result<PlayerCapabilityContract> {
        val sessionId = activeBundle?.session?.sessionId
            ?: return Result.failure(IllegalStateException("No active Media3 session"))
        return sessionController.refreshSession(sessionId)
            .onSuccess { activeBundle = it }
            .map { it.capabilityContract }
    }

    suspend fun dispatchCapability(
        capability: Media3Capability,
        payload: Map<String, @JvmSuppressWildcards Any?>? = null
    ): Result<CapabilityCommandResponseData> {
        val sessionId = activeBundle?.session?.sessionId
            ?: return Result.failure(IllegalStateException("No active Media3 session"))
        return sessionController.dispatchCapability(sessionId, capability, payload)
            .onSuccess {
                // Update cached snapshot of session state if the backend mutated it.
                sessionController.refreshSession(sessionId)
                    .onSuccess { activeBundle = it }
            }
    }

    fun currentSession(): PlaybackSession? = activeBundle?.session

    fun currentCapability(): PlayerCapabilityContract? = activeBundle?.capabilityContract

    fun rolloutSnapshot(): RolloutToggleSnapshot? {
        val sessionId = activeBundle?.session?.sessionId
        return snapshotManager.snapshot(sessionId)
    }

    fun startupLatencyMs(): Long? {
        val start = sessionStartAt ?: return null
        val firstFrame = firstFrameAt ?: return null
        return firstFrame - start
    }

    fun isStartupWithinTarget(targetMs: Long = STARTUP_BUDGET_MS): Boolean {
        val latency = startupLatencyMs() ?: return false
        return latency <= targetMs
    }

    fun markFirstFrame() {
        if (sessionStartAt == null) {
            return
        }
        firstFrameAt = timeProvider()
    }

    companion object {
        const val STARTUP_BUDGET_MS = 2_000L
    }
}
