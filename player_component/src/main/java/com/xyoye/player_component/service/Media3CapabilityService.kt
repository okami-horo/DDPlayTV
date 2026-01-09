package com.xyoye.player_component.service

import android.content.Context
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.config.Media3ToggleProvider
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.network.repository.Media3Repository
import com.xyoye.common_component.service.Media3CapabilityProvider
import com.xyoye.data_component.data.media3.CapabilityCommandResponseData
import com.xyoye.data_component.data.media3.PlaybackSessionRequestData
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3SessionBundle
import com.xyoye.data_component.entity.media3.Media3SourceType
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import kotlin.jvm.JvmSuppressWildcards

@Route(
    path = RouteTable.Player.Media3CapabilityProvider,
    name = "Media3 capability provider",
)
class Media3CapabilityService : Media3CapabilityProvider {
    override fun init(context: Context?) = Unit

    override suspend fun prepareSession(
        mediaId: String,
        sourceType: Media3SourceType,
        requestedCapabilities: List<Media3Capability>,
        autoplay: Boolean
    ): Result<Media3SessionBundle> {
        val snapshot = Media3ToggleProvider.snapshot()
        if (!snapshot.value) {
            return Result.failure(IllegalStateException("Media3 is disabled by rollout snapshot"))
        }
        val request =
            PlaybackSessionRequestData(
                mediaId = mediaId,
                sourceType = sourceType,
                autoplay = autoplay,
                requestedCapabilities = requestedCapabilities,
            )
        return Media3Repository.createSession(request)
    }

    override suspend fun refreshSession(sessionId: String): Result<Media3SessionBundle> = Media3Repository.fetchSession(sessionId)

    override suspend fun dispatchCapability(
        sessionId: String,
        capability: Media3Capability,
        payload: Map<String, @JvmSuppressWildcards Any?>?
    ): Result<CapabilityCommandResponseData> = Media3Repository.dispatchCapability(sessionId, capability, payload)

    override fun cachedCapability(sessionId: String): PlayerCapabilityContract? = Media3Repository.cachedCapability(sessionId)

    override fun cachedToggle(sessionId: String): RolloutToggleSnapshot? = Media3Repository.cachedToggle(sessionId)
}
