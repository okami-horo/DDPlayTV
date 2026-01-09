package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliCookieInfoData(
    val refresh: Boolean = false,
    val timestamp: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class BilibiliCookieRefreshData(
    val status: Int? = null,
    val message: String? = null,
    @Json(name = "refresh_token")
    val refreshToken: String? = null,
)

