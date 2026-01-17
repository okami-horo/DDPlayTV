package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliPgcPlayurlV2Result(
    @Json(name = "video_info")
    val videoInfo: BilibiliPlayurlData? = null
)
