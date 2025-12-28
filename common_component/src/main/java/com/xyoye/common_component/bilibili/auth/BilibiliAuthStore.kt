package com.xyoye.common_component.bilibili.auth

import com.tencent.mmkv.MMKV

/**
 * Bilibili 登录态存储（按媒体库 storageKey 隔离）。
 *
 * 存储范围：
 * - refresh_token：二维码登录成功返回，用于后续刷新链路（可选）
 * - csrf（bili_jct）、mid（DedeUserID）：从 Cookie 提取，用于部分接口/调试
 * - updatedAt：最后一次成功登录/刷新时间
 */
object BilibiliAuthStore {
    private const val MMKV_ID = "bilibili_auth_store"

    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_CSRF = "csrf"
    private const val KEY_MID = "mid"
    private const val KEY_UPDATED_AT = "updated_at"

    data class AuthState(
        val refreshToken: String? = null,
        val csrf: String? = null,
        val mid: Long? = null,
        val updatedAt: Long = 0L,
    )

    fun read(storageKey: String): AuthState {
        val kv = mmkv()
        val refreshToken = kv.decodeString(namespacedKey(storageKey, KEY_REFRESH_TOKEN))
        val csrf = kv.decodeString(namespacedKey(storageKey, KEY_CSRF))
        val mid = kv.decodeLong(namespacedKey(storageKey, KEY_MID), -1L).takeIf { it > 0 }
        val updatedAt = kv.decodeLong(namespacedKey(storageKey, KEY_UPDATED_AT), 0L)
        return AuthState(
            refreshToken = refreshToken,
            csrf = csrf,
            mid = mid,
            updatedAt = updatedAt,
        )
    }

    fun write(
        storageKey: String,
        refreshToken: String? = null,
        csrf: String? = null,
        mid: Long? = null,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        val kv = mmkv()
        refreshToken?.let { kv.encode(namespacedKey(storageKey, KEY_REFRESH_TOKEN), it) }
        csrf?.let { kv.encode(namespacedKey(storageKey, KEY_CSRF), it) }
        mid?.let { kv.encode(namespacedKey(storageKey, KEY_MID), it) }
        kv.encode(namespacedKey(storageKey, KEY_UPDATED_AT), updatedAt)
    }

    fun updateFromCookies(
        storageKey: String,
        cookieJarStore: BilibiliCookieJarStore,
        refreshToken: String? = null,
    ) {
        val cookieHeader = cookieJarStore.exportCookieHeader() ?: ""
        val csrf = extractCookie(cookieHeader, "bili_jct")
        val mid = extractCookie(cookieHeader, "DedeUserID")?.toLongOrNull()
        write(
            storageKey = storageKey,
            refreshToken = refreshToken,
            csrf = csrf,
            mid = mid,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun clear(storageKey: String) {
        val kv = mmkv()
        kv.removeValueForKey(namespacedKey(storageKey, KEY_REFRESH_TOKEN))
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
