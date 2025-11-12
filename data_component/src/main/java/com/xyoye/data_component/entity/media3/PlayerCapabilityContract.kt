package com.xyoye.data_component.entity.media3

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PlayerCapabilityContract(
    val sessionId: String,
    val capabilities: List<Media3Capability>,
    val seekIncrementMs: Int = 5_000,
    val speedOptions: List<Float> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    val backgroundModes: List<Media3BackgroundMode> = emptyList(),
    val castTargets: List<CastTarget> = emptyList(),
    val offlineSupport: OfflineSupport? = null,
    val sessionCommands: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CastTarget(
    val id: String,
    val name: String,
    val type: CastTargetType = CastTargetType.CHROMECAST
)

@JsonClass(generateAdapter = true)
data class OfflineSupport(
    val downloadResume: Boolean = false,
    val verifyBeforePlay: Boolean = false,
    val audioOnlyFallback: Boolean = false
)
