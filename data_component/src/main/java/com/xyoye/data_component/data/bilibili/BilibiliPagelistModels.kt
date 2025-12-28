package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliPagelistItem(
    val cid: Long = 0,
    val page: Int = 0,
    val part: String? = null,
    val duration: Long = 0,
)

