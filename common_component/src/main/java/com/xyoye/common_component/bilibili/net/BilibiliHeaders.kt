package com.xyoye.common_component.bilibili.net

/**
 * Bilibili 请求头策略（Web API）。
 *
 * 目标：
 * - 统一 User-Agent / Referer / Accept-Encoding
 * - 生成播放器侧需要的 Header（Cookie 等）
 * - 提供脱敏工具，避免日志输出敏感信息
 */
object BilibiliHeaders {
    const val HEADER_COOKIE = "Cookie"
    const val HEADER_REFERER = "Referer"
    const val HEADER_USER_AGENT = "User-Agent"
    const val HEADER_ACCEPT_ENCODING = "Accept-Encoding"

    const val REFERER = "https://www.bilibili.com/"

    // 桌面浏览器 UA（Web API 更稳定）
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    const val ACCEPT_ENCODING = "gzip,deflate"

    fun defaultHeaders(): Map<String, String> = defaultHeaders(REFERER)

    fun defaultHeaders(referer: String): Map<String, String> =
        mapOf(
            HEADER_USER_AGENT to USER_AGENT,
            HEADER_REFERER to referer,
            HEADER_ACCEPT_ENCODING to ACCEPT_ENCODING,
        )

    fun withCookie(
        cookieHeader: String?,
        referer: String = REFERER,
    ): Map<String, String> {
        val headers = defaultHeaders(referer).toMutableMap()
        cookieHeader?.takeIf { it.isNotBlank() }?.let {
            headers[HEADER_COOKIE] = it
        }
        return headers
    }

    fun redactCookie(cookieHeader: String?): String =
        if (cookieHeader.isNullOrBlank()) {
            ""
        } else {
            "Cookie(<redacted>)"
        }

    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (key, value) ->
            when (key) {
                HEADER_COOKIE -> redactCookie(value)
                else -> value
            }
        }
}
