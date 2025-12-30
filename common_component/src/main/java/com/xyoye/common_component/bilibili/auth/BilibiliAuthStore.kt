package com.xyoye.common_component.bilibili.auth

import com.tencent.mmkv.MMKV

/**
 * Bilibili 登录态存储（按媒体库 storageKey 隔离）。
 *
 * 存储范围：
 * - web refresh_token：Web 端扫码登录成功返回，用于 Cookie 刷新链路（可选）
 * - app access_token/refresh_token：TV 端扫码登录成功返回，用于 APP/TV API 鉴权/刷新（可选）
 * - csrf（bili_jct）、mid（DedeUserID）：从 Cookie 提取，用于部分接口/调试
 * - updatedAt：最后一次成功登录/刷新时间
 */
object BilibiliAuthStore {
    private const val MMKV_ID = "bilibili_auth_store"

    // Web 端扫码登录成功返回，用于 Cookie 刷新链路
    private const val KEY_WEB_REFRESH_TOKEN = "refresh_token"

    // TV 端扫码登录成功返回，用于 APP/TV API 鉴权（作为 access_key 传参）
    private const val KEY_APP_ACCESS_TOKEN = "app_access_token"

    // TV 端扫码登录成功返回，用于 access_token 刷新链路（本项目暂不实现刷新，只做存储）
    private const val KEY_APP_REFRESH_TOKEN = "app_refresh_token"

    private const val KEY_CSRF = "csrf"
    private const val KEY_MID = "mid"
    private const val KEY_UPDATED_AT = "updated_at"

    data class AuthState(
        val webRefreshToken: String? = null,
        val appAccessToken: String? = null,
        val appRefreshToken: String? = null,
        val csrf: String? = null,
        val mid: Long? = null,
        val updatedAt: Long = 0L,
    )

    fun read(storageKey: String): AuthState {
        val kv = mmkv()
        val webRefreshToken = kv.decodeString(namespacedKey(storageKey, KEY_WEB_REFRESH_TOKEN))
        val appAccessToken = kv.decodeString(namespacedKey(storageKey, KEY_APP_ACCESS_TOKEN))
        val appRefreshToken = kv.decodeString(namespacedKey(storageKey, KEY_APP_REFRESH_TOKEN))
        val csrf = kv.decodeString(namespacedKey(storageKey, KEY_CSRF))
        val mid = kv.decodeLong(namespacedKey(storageKey, KEY_MID), -1L).takeIf { it > 0 }
        val updatedAt = kv.decodeLong(namespacedKey(storageKey, KEY_UPDATED_AT), 0L)
        return AuthState(
            webRefreshToken = webRefreshToken,
            appAccessToken = appAccessToken,
            appRefreshToken = appRefreshToken,
            csrf = csrf,
            mid = mid,
            updatedAt = updatedAt,
        )
    }

    fun write(
        storageKey: String,
        webRefreshToken: String? = null,
        appAccessToken: String? = null,
        appRefreshToken: String? = null,
        csrf: String? = null,
        mid: Long? = null,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        val kv = mmkv()
        webRefreshToken?.let { kv.encode(namespacedKey(storageKey, KEY_WEB_REFRESH_TOKEN), it) }
        appAccessToken?.let { kv.encode(namespacedKey(storageKey, KEY_APP_ACCESS_TOKEN), it) }
        appRefreshToken?.let { kv.encode(namespacedKey(storageKey, KEY_APP_REFRESH_TOKEN), it) }
        csrf?.let { kv.encode(namespacedKey(storageKey, KEY_CSRF), it) }
        mid?.let { kv.encode(namespacedKey(storageKey, KEY_MID), it) }
        kv.encode(namespacedKey(storageKey, KEY_UPDATED_AT), updatedAt)
    }

    fun updateFromCookies(
        storageKey: String,
        cookieJarStore: BilibiliCookieJarStore,
        webRefreshToken: String? = null,
    ) {
        val cookieHeader = cookieJarStore.exportCookieHeader() ?: ""
        val csrf = extractCookie(cookieHeader, "bili_jct")
        val mid = extractCookie(cookieHeader, "DedeUserID")?.toLongOrNull()
        write(
            storageKey = storageKey,
            webRefreshToken = webRefreshToken,
            csrf = csrf,
            mid = mid,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun updateAppTokens(
        storageKey: String,
        accessToken: String?,
        refreshToken: String?,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        write(
            storageKey = storageKey,
            appAccessToken = accessToken,
            appRefreshToken = refreshToken,
            updatedAt = updatedAt,
        )
    }

    fun clear(storageKey: String) {
        val kv = mmkv()
        kv.removeValueForKey(namespacedKey(storageKey, KEY_WEB_REFRESH_TOKEN))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_APP_ACCESS_TOKEN))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_APP_REFRESH_TOKEN))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_CSRF))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_MID))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_UPDATED_AT))
    }

    private fun mmkv(): MMKV = MMKV.mmkvWithID(MMKV_ID)

    private fun namespacedKey(
        storageKey: String,
        fieldKey: String
    ): String = "$storageKey.$fieldKey"

    private fun extractCookie(
        cookieHeader: String,
        name: String,
    ): String? {
        val token = "$name="
        val index = cookieHeader.indexOf(token)
        if (index < 0) return null
        val start = index + token.length
        val end = cookieHeader.indexOf(';', start).let { if (it < 0) cookieHeader.length else it }
        return cookieHeader.substring(start, end).trim().ifEmpty { null }
    }
}
