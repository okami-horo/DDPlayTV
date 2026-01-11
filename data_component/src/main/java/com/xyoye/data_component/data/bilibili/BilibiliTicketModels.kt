package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliWebTicketData(
    val ticket: String = "",
    @Json(name = "created_at")
    val createdAt: Long = 0,
    /**
     * 有效期（秒）
     */
    val ttl: Long = 0,
    /**
     * WBI 相关字段（可用于更新 wbi_img）
     */
    val nav: BilibiliWebTicketNavData? = null
)

@JsonClass(generateAdapter = true)
data class BilibiliWebTicketNavData(
    val img: String? = null,
    val sub: String? = null
)
