package com.xyoye.data_component.entity.media3

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DownloadAssetCheck(
    val downloadId: String,
    val mediaId: String,
    val lastVerifiedAt: Long? = null,
    val isCompatible: Boolean,
    val requiredAction: DownloadRequiredAction,
    val verificationLogs: List<String> = emptyList()
)
