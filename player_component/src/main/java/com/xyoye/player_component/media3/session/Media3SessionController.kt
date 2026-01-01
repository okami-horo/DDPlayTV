package com.xyoye.player_component.media3.session

import com.xyoye.common_component.network.repository.Media3Repository
import com.xyoye.data_component.data.media3.CapabilityCommandResponseData
import com.xyoye.data_component.data.media3.PlaybackSessionRequestData
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3SessionBundle
import com.xyoye.data_component.entity.media3.Media3SourceType
import kotlin.jvm.JvmSuppressWildcards

/**
 * Session controller responsible for bridging delegate requests to Media3Repository.
 */
open class Media3SessionController {
    open suspend fun prepareSession(
        mediaId: String,
        sourceType: Media3SourceType,
        requestedCapabilities: List<Media3Capability>,
        autoplay: Boolean
    ): Result<Media3SessionBundle> {
        val request =
            PlaybackSessionRequestData(
                mediaId = mediaId,
                sourceType = sourceType,
                autoplay = autoplay,
                requestedCapabilities = requestedCapabilities,
            )
        return Media3Repository.createSession(request)
    }

    open suspend fun refreshSession(sessionId: String): Result<Media3SessionBundle> = Media3Repository.fetchSession(sessionId)

    open suspend fun dispatchCapability(
        sessionId: String,
        capability: Media3Capability,
        payload: Map<String, @JvmSuppressWildcards Any?>?
    ): Result<CapabilityCommandResponseData> = Media3Repository.dispatchCapability(sessionId, capability, payload)

    open fun cachedSession(sessionId: String): Media3SessionBundle? {
        val session = Media3Repository.cachedSession(sessionId) ?: return null
        val capability = Media3Repository.cachedCapability(sessionId) ?: return null
        val snapshot = Media3Repository.cachedToggle(sessionId) ?: return null
        return Media3SessionBundle(session, capability, snapshot)
    }
}
