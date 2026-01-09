package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BilibiliResultJsonModel<T>(
    val code: Int = 0,
    val message: String = "",
    val result: T? = null,
) {
    val isSuccess: Boolean get() = code == 0

    val successData: T? get() = if (isSuccess) result else null
}

