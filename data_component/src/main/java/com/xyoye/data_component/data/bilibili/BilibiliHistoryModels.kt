package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliHistoryCursorData(
    val cursor: BilibiliHistoryCursor? = null,
    val list: List<BilibiliHistoryItem> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class BilibiliHistoryCursor(
    val max: Long = 0,
    @Json(name = "view_at")
    val viewAt: Long = 0,
    val business: String = "",
    val ps: Int = 0,
)

@JsonClass(generateAdapter = true)
data class BilibiliHistoryItem(
    val title: String = "",
    val cover: String? = null,
    @Json(name = "author_name")
    val authorName: String? = null,
    @Json(name = "view_at")
    val viewAt: Long = 0,
    @Json(name = "progress")
    val progressSec: Long = 0,
    @Json(name = "duration")
    val durationSec: Long = 0,
    val videos: Int = 0,
    val history: BilibiliHistoryItemHistory? = null,
)

@JsonClass(generateAdapter = true)
data class BilibiliHistoryItemHistory(
    val bvid: String = "",
    val cid: Long = 0,
    val business: String? = null,
    val page: Int = 0,
    val part: String? = null,
)

