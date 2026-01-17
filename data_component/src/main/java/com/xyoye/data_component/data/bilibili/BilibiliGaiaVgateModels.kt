package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliGaiaVgateRegisterData(
    val type: String? = null,
    val token: String? = null,
    val geetest: BilibiliGeetestData? = null,
    val biliword: Any? = null,
    val phone: Any? = null,
    val sms: Any? = null
)

@JsonClass(generateAdapter = true)
data class BilibiliGeetestData(
    val gt: String? = null,
    val challenge: String? = null
)

@JsonClass(generateAdapter = true)
data class BilibiliGaiaVgateValidateData(
    @Json(name = "is_valid")
    val isValid: Int? = null,
    @Json(name = "grisk_id")
    val griskId: String? = null
)
