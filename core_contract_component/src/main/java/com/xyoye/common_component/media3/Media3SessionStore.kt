package com.xyoye.common_component.media3

import com.xyoye.data_component.entity.media3.Media3SessionBundle
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight in-memory cache that exposes the latest Media3 session + capability contract
 * so non-player modules (storage/local/download) can align their UX with the active rollout.
 */
object Media3SessionStore {
    private val sessionState = MutableStateFlow<PlaybackSession?>(null)
    private val capabilityState = MutableStateFlow<PlayerCapabilityContract?>(null)

    fun update(bundle: Media3SessionBundle) {
        sessionState.value = bundle.session
        capabilityState.value = bundle.capabilityContract
    }

    fun clear() {
        sessionState.value = null
        capabilityState.value = null
    }

    fun currentSession(): PlaybackSession? = sessionState.value

    fun currentCapability(): PlayerCapabilityContract? = capabilityState.value

    fun sessionFlow(): StateFlow<PlaybackSession?> = sessionState

    fun capabilityFlow(): StateFlow<PlayerCapabilityContract?> = capabilityState
}
