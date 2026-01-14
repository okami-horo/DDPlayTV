package com.xyoye.common_component.storage.open115.net

import com.xyoye.common_component.network.config.HeaderKey

/**
 * 115 Open 请求头策略。
 *
 * 目标：
 * - 统一 User-Agent（与 OpenList 默认值保持一致）
 * - 统一 Bearer 拼装
 * - 提供脱敏工具，避免日志输出敏感信息
 */
object Open115Headers {
    const val HEADER_USER_AGENT = "User-Agent"

    // OpenList/drivers/base/client.go: UserAgentNT
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Safari/537.36 Chrome/142.0.0.0 OpenList/425.6.30"

    fun bearer(
        accessToken: String
    ): String = "Bearer $accessToken"

    fun redactToken(token: String?): String =
        if (token.isNullOrBlank()) {
            ""
        } else {
            "<redacted len=${token.length}>"
        }

    fun redactAuthorization(authorization: String?): String {
        val raw = authorization?.trim().orEmpty()
        val token =
            raw.removePrefix("Bearer").trim()
                .takeIf { it.isNotBlank() }
        return if (token.isNullOrBlank()) {
            ""
        } else {
            "Bearer ${redactToken(token)}"
        }
    }

    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (key, value) ->
            when (key) {
                HeaderKey.AUTHORIZATION -> redactAuthorization(value)
                else -> value
            }
        }
}

