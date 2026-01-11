package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliLiveRoomInfoData(
    @Json(name = "room_id")
    val roomId: Long = 0,
    @Json(name = "short_id")
    val shortId: Long = 0,
    val title: String = "",
    @Json(name = "user_cover")
    val userCover: String? = null,
    @Json(name = "live_status")
    val liveStatus: Int = 0
)

@JsonClass(generateAdapter = true)
data class BilibiliLivePlayUrlData(
    val durl: List<BilibiliLivePlayUrlDurl> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BilibiliLivePlayUrlDurl(
    val url: String = "",
    val order: Int = 0
)
