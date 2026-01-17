package com.xyoye.common_component.bilibili.risk

/**
 * GAIA 风控网关激活请求体。
 *
 * 对应：`POST /x/internal/gaia-gateway/ExClimbWuzhi`
 */
data class BilibiliGaiaActivateRequest(
    val payload: String
)
