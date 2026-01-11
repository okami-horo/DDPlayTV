package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliQrcodeGenerateData(
    val url: String = "",
    @Json(name = "qrcode_key")
    val qrcodeKey: String = ""
)

@JsonClass(generateAdapter = true)
data class BilibiliQrcodePollData(
    val url: String? = null,
    @Json(name = "refresh_token")
    val refreshToken: String? = null,
    val timestamp: Long? = null,
    @Json(name = "code")
    val statusCode: Int? = null,
    @Json(name = "message")
    val statusMessage: String? = null
)
