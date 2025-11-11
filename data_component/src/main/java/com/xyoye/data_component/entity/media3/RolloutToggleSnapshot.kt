package com.xyoye.data_component.entity.media3

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RolloutToggleSnapshot(
    val snapshotId: String,
    val flagName: String = "media3_enabled",
    val value: Boolean,
    val source: Media3RolloutSource,
    val evaluatedAt: Long,
    val appliesToSession: String? = null
)
