package com.xyoye.data_component.entity.media3

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.squareup.moshi.JsonClass
import com.xyoye.data_component.helper.Media3Converters

@JsonClass(generateAdapter = true)
@TypeConverters(Media3Converters::class)
@Entity(tableName = "media3_download_asset_check")
data class DownloadAssetCheck(
    @PrimaryKey
    val downloadId: String,
    val mediaId: String,
    val lastVerifiedAt: Long? = null,
    val isCompatible: Boolean,
    val requiredAction: DownloadRequiredAction,
    val verificationLogs: List<String> = emptyList()
)
