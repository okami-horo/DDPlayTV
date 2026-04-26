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
    val order: Int = 0,
    @Json(name = "backup_url")
    val backupUrl: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BilibiliLiveRoomPlayInfoData(
    @Json(name = "room_id")
    val roomId: Long = 0,
    @Json(name = "live_status")
    val liveStatus: Int = 0,
    @Json(name = "playurl_info")
    val playurlInfo: BilibiliLivePlayurlInfoData? = null
)

@JsonClass(generateAdapter = true)
data class BilibiliLivePlayurlInfoData(
    val playurl: BilibiliLiveRoomPlayurlPayloadData? = null
)

@JsonClass(generateAdapter = true)
data class BilibiliLiveRoomPlayurlPayloadData(
    val stream: List<BilibiliLiveStreamData> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BilibiliLiveStreamData(
    val format: List<BilibiliLiveFormatData> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BilibiliLiveFormatData(
    val codec: List<BilibiliLiveCodecData> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BilibiliLiveCodecData(
    @Json(name = "current_qn")
    val currentQn: Int = 0,
    @Json(name = "accept_qn")
    val acceptQn: List<Int> = emptyList(),
    @Json(name = "base_url")
    val baseUrl: String = "",
    @Json(name = "url_info")
    val urlInfo: List<BilibiliLiveUrlInfoData> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BilibiliLiveUrlInfoData(
    val host: String = "",
    val extra: String = ""
)
