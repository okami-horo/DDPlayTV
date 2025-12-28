package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliQrcodeGenerateData(
    val url: String = "",
    @Json(name = "qrcode_key")
    val qrcodeKey: String = "",
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
    val statusMessage: String? = null,
)

/**
 * Bilibili TV/App 扫码登录
 *
 * - auth_code：用于轮询登录状态
 * - url：二维码内容
 */
@JsonClass(generateAdapter = true)
data class BilibiliAppQrcodeAuthCodeData(
    val url: String = "",
    @Json(name = "auth_code")
    val authCode: String = "",
)

@JsonClass(generateAdapter = true)
data class BilibiliAppQrcodePollData(
    @Json(name = "refresh_token")
    val refreshToken: String? = null,
    @Json(name = "cookie_info")
    val cookieInfo: CookieInfo? = null,
) {
    @JsonClass(generateAdapter = true)
    data class CookieInfo(
        val cookies: List<CookieItem> = emptyList(),
        val domains: List<String> = emptyList(),
    ) {
        @JsonClass(generateAdapter = true)
        data class CookieItem(
            val name: String = "",
            val value: String = "",
            @Json(name = "http_only")
            val httpOnly: Int = 0,
            val expires: Long = 0L,
            val secure: Int = 0,
        )
    }
}
