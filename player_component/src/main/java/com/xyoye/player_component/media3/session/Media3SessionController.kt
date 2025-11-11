package com.xyoye.player_component.media3.session

import com.xyoye.common_component.network.repository.Media3SessionBundle
import com.xyoye.data_component.data.media3.CapabilityCommandResponseData
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3SourceType
import kotlin.jvm.JvmSuppressWildcards

/**
 * Session controller responsible for bridging delegate requests to Media3Repository.
 * Implementation completed under T016.
 */
open class Media3SessionController {

    open suspend fun prepareSession(
        mediaId: String,
        sourceType: Media3SourceType,
        requestedCapabilities: List<Media3Capability>,
        autoplay: Boolean
    ): Result<Media3SessionBundle> {
        TODO("Not yet implemented")
    }

    open suspend fun refreshSession(sessionId: String): Result<Media3SessionBundle> {
        TODO("Not yet implemented")
    }

    open suspend fun dispatchCapability(
        sessionId: String,
        capability: Media3Capability,
        payload: Map<String, @JvmSuppressWildcards Any?>?
    ): Result<CapabilityCommandResponseData> {
        TODO("Not yet implemented")
    }

    open fun cachedSession(sessionId: String): Media3SessionBundle? {
        TODO("Not yet implemented")
    }
}
