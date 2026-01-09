package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliTvQrcodeAuthCodeData(
    val url: String = "",
    @Json(name = "auth_code")
    val authCode: String = "",
)

/**
 * TV 端扫码登录轮询接口返回结构：
 * - code 非 0 时，data 可能为 null（例如 86039/86090/86038）。
 */
@JsonClass(generateAdapter = true)
data class BilibiliTvQrcodePollModel(
    val code: Int = 0,
    val message: String = "",
    val ttl: Int? = null,
    val data: BilibiliTvQrcodePollData? = null,
)

@JsonClass(generateAdapter = true)
data class BilibiliTvQrcodePollData(
    val mid: Long? = null,
    @Json(name = "access_token")
    val accessToken: String? = null,
    @Json(name = "refresh_token")
    val refreshToken: String? = null,
    @Json(name = "expires_in")
    val expiresIn: Long? = null,
    @Json(name = "cookie_info")
    val cookieInfo: BilibiliTvCookieInfo? = null,
)

@JsonClass(generateAdapter = true)
data class BilibiliTvCookieInfo(
    val cookies: List<BilibiliTvCookieItem> = emptyList(),
    val domains: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class BilibiliTvCookieItem(
    val name: String = "",
    val value: String = "",
    @Json(name = "http_only")
    val httpOnly: Int? = null,
    val expires: Long? = null,
    val secure: Int? = null,
)

