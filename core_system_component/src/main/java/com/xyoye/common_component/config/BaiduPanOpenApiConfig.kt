package com.xyoye.common_component.config

import com.xyoye.core_system_component.BuildConfig

/**
 * 百度网盘开放平台 OpenAPI 配置读取封装
 *
 * 统一从 BuildConfig 读取编译期注入的 client_id/client_secret，避免在业务层散落读取逻辑。
 */
object BaiduPanOpenApiConfig {
    val clientId: String
        get() = BuildConfig.BAIDU_PAN_CLIENT_ID.trim()

    val clientSecret: String
        get() = BuildConfig.BAIDU_PAN_CLIENT_SECRET.trim()

    fun isConfigured(): Boolean = clientId.isNotBlank() && clientSecret.isNotBlank()
}
