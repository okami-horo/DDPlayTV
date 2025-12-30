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
    @Json(name = "long_title")
    val longTitle: String? = null,
    val cover: String? = null,
    /**
     * 重定向 URL
     *
     * 仅用于剧集/直播等条目（例如：`https://live.bilibili.com/{roomId}`）
     */
    val uri: String? = null,
    @Json(name = "author_name")
    val authorName: String? = null,
    @Json(name = "view_at")
    val viewAt: Long = 0,
    @Json(name = "progress")
    val progressSec: Long = 0,
    @Json(name = "duration")
    val durationSec: Long = 0,
    val videos: Int = 0,
    val badge: String? = null,
    @Json(name = "show_title")
    val showTitle: String? = null,
    val total: Int = 0,
    /**
     * 目标 id（不同 business 下含义不同）
     *
     * - archive: avid
     * - live: roomId（直播间号）
     * - pgc: ssid（season id）
     */
    val kid: Long = 0,
    @Json(name = "tag_name")
    val tagName: String? = null,
    @Json(name = "live_status")
    val liveStatus: Int? = null,
    val history: BilibiliHistoryItemHistory? = null,
)

@JsonClass(generateAdapter = true)
data class BilibiliHistoryItemHistory(
    /**
     * 目标 id
     *
     * - archive: avid
     * - live: roomId（直播间号）
     */
    val oid: Long = 0,
    /**
     * PGC 剧集 epid（仅 business=pgc 有效）
     */
    val epid: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    val business: String? = null,
    val page: Int = 0,
    val part: String? = null,
)
