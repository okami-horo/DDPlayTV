package com.xyoye.data_component.data.baidupan.oauth

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BaiduPanDeviceCodeResponse(
    @Json(name = "device_code")
    val deviceCode: String = "",
    @Json(name = "user_code")
    val userCode: String = "",
    @Json(name = "verification_url")
    val verificationUrl: String? = null,
    @Json(name = "qrcode_url")
    val qrcodeUrl: String = "",
    @Json(name = "expires_in")
    val expiresIn: Int = 0,
    val interval: Int = 0
)

@JsonClass(generateAdapter = true)
data class BaiduPanTokenResponse(
    @Json(name = "access_token")
    val accessToken: String = "",
    @Json(name = "refresh_token")
    val refreshToken: String = "",
    @Json(name = "expires_in")
    val expiresIn: Int = 0,
    val scope: String? = null
)

@JsonClass(generateAdapter = true)
data class BaiduPanOAuthError(
    val error: String? = null,
    @Json(name = "error_description")
    val errorDescription: String? = null,
    @Json(name = "error_uri")
    val errorUri: String? = null
)
