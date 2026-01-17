package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.JsonClass

/**
 * PGC 播放地址（APP/TV 接口）：
 * - `GET /pgc/player/api/playurl`
 * - 返回结构非 `result` 包裹，而是直接在根对象包含 dash/durl 等字段
 */
@JsonClass(generateAdapter = true)
data class BilibiliPgcPlayurlApiModel(
    val code: Int = 0,
    val message: String = "",
    val dash: BilibiliDashData? = null,
    val durl: List<BilibiliDurlData> = emptyList()
)
