package com.xyoye.common_component.service

import com.alibaba.android.arouter.facade.template.IProvider
import com.xyoye.common_component.network.repository.Media3SessionBundle
import com.xyoye.data_component.data.media3.CapabilityCommandResponseData
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3SourceType
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import kotlin.jvm.JvmSuppressWildcards

interface Media3CapabilityProvider : IProvider {

    suspend fun prepareSession(
        mediaId: String,
        sourceType: Media3SourceType,
        requestedCapabilities: List<Media3Capability> = emptyList(),
        autoplay: Boolean = true
    ): Result<Media3SessionBundle>

    suspend fun refreshSession(
        sessionId: String
    ): Result<Media3SessionBundle>

    suspend fun dispatchCapability(
        sessionId: String,
        capability: Media3Capability,
        payload: Map<String, @JvmSuppressWildcards Any?>? = null
    ): Result<CapabilityCommandResponseData>

    fun cachedCapability(sessionId: String): PlayerCapabilityContract?

    fun cachedToggle(sessionId: String): RolloutToggleSnapshot?
}
