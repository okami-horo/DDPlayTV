package com.xyoye.data_component.entity.media3

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.squareup.moshi.JsonClass
import com.xyoye.data_component.helper.Media3Converters

@JsonClass(generateAdapter = true)
@TypeConverters(Media3Converters::class)
@Entity(tableName = "media3_rollout_snapshot")
data class RolloutToggleSnapshot(
    @PrimaryKey
    val snapshotId: String,
    val flagName: String = "media3_enabled",
    val value: Boolean,
    val source: Media3RolloutSource,
    val evaluatedAt: Long,
    val appliesToSession: String? = null
)
