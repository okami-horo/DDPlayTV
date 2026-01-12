package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliLiveFollowData(
    val title: String = "",
    val pageSize: Int = 0,
    val totalPage: Int = 0,
    val list: List<BilibiliLiveFollowItem> = emptyList(),
    val count: Int = 0,
    @Json(name = "never_lived_count")
    val neverLivedCount: Int = 0,
    @Json(name = "live_count")
    val liveCount: Int = 0,
)

@JsonClass(generateAdapter = true)
data class BilibiliLiveFollowItem(
    @Json(name = "roomid")
    val roomId: Long = 0,
    val uid: Long = 0,
    val uname: String = "",
    val title: String = "",
    val face: String? = null,
    @Json(name = "live_status")
    val liveStatus: Int = 0,
    @Json(name = "room_cover")
    val roomCover: String? = null,
    @Json(name = "text_small")
    val textSmall: String? = null,
)
