package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliNavData(
    val isLogin: Boolean = false,
    @Json(name = "wbi_img")
    val wbiImg: BilibiliWbiImg? = null,
)

@JsonClass(generateAdapter = true)
data class BilibiliWbiImg(
    @Json(name = "img_url")
    val imgUrl: String? = null,
    @Json(name = "sub_url")
    val subUrl: String? = null,
)

