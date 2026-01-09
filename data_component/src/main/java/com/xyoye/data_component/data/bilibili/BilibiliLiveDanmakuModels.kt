package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliLiveDanmuInfoData(
    val token: String = "",
    @Json(name = "host_list")
    val hostList: List<BilibiliLiveDanmuHost> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class BilibiliLiveDanmuHost(
    val host: String = "",
    val port: Int = 0,
    @Json(name = "wss_port")
    val wssPort: Int = 0,
    @Json(name = "ws_port")
    val wsPort: Int = 0,
)

data class BilibiliLiveDanmuConnectInfo(
    val roomId: Long,
    val token: String,
    val hostList: List<BilibiliLiveDanmuHost>,
)

sealed interface LiveDanmakuEvent {
    data class Danmaku(
        val text: String,
        val mode: DanmakuMode,
        val color: Int,
        val timestampMs: Long,
        val recommendScore: Int = 0,
        val userId: Long = 0,
        val userName: String = "",
    ) : LiveDanmakuEvent

    data class Popularity(
        val value: Long,
    ) : LiveDanmakuEvent

    enum class DanmakuMode {
        SCROLL,
        TOP,
        BOTTOM,
    }
}
