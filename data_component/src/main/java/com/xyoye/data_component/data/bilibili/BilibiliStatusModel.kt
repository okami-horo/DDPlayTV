package com.xyoye.data_component.data.bilibili

import com.squareup.moshi.JsonClass

/**
 * Bilibili 状态响应模型。
 *
 * 与 [BilibiliJsonModel] 的差异：
 * - 部分接口会复用 code/message 表达“状态”（例如 TV/App 扫码轮询），code 非 0 不一定是错误。
 */
@JsonClass(generateAdapter = true)
data class BilibiliStatusModel<T>(
    val code: Int = 0,
    val message: String = "",
    val ttl: Int? = null,
    val data: T? = null,
)

