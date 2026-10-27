package com.xyoye.data_component.media3.dto

import com.squareup.moshi.JsonClass
import com.xyoye.data_component.media3.entity.DownloadRequiredAction

@JsonClass(generateAdapter = true)
data class DownloadValidationRequestData(
    val downloadId: String,
    val mediaId: String,
    val media3Version: String,
    val lastVerifiedAt: Long? = null
)

@JsonClass(generateAdapter = true)
data class DownloadValidationResponseData(
    val downloadId: String,
    val isCompatible: Boolean,
    val requiredAction: DownloadRequiredAction,
    val verificationLogs: List<String> = emptyList()
)
