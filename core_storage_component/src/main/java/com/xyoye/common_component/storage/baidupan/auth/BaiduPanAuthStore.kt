package com.xyoye.common_component.storage.baidupan.auth

import com.tencent.mmkv.MMKV
import com.xyoye.data_component.entity.MediaLibraryEntity

/**
 * 百度网盘授权态存储（按媒体库 storageKey 隔离）。
 *
 * 设计说明：
 * - 按媒体库唯一键隔离：storageKey = "${mediaType.value}:${url}"
 * - 避免新增媒体库时无法拿到自增 id 的问题
 */
object BaiduPanAuthStore {
    private const val MMKV_ID = "baidu_pan_auth_store"

    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_SCOPE = "scope"

    private const val KEY_UK = "uk"
    private const val KEY_NETDISK_NAME = "netdisk_name"
    private const val KEY_AVATAR_URL = "avatar_url"

    private const val KEY_UPDATED_AT_MS = "updated_at_ms"

    data class AuthState(
        val accessToken: String? = null,
        val expiresAtMs: Long = 0L,
        val refreshToken: String? = null,
        val scope: String? = null,
        val uk: Long? = null,
        val netdiskName: String? = null,
        val avatarUrl: String? = null,
        val updatedAtMs: Long = 0L
    ) {
        fun isAuthorized(): Boolean = refreshToken.isNullOrBlank().not() && uk != null && uk > 0
    }

    fun storageKey(library: MediaLibraryEntity): String = "${library.mediaType.value}:${library.url.trim().removeSuffix("/")}"

    fun read(storageKey: String): AuthState {
        val kv = mmkv()
        val accessToken = kv.decodeString(namespacedKey(storageKey, KEY_ACCESS_TOKEN))
        val expiresAtMs = kv.decodeLong(namespacedKey(storageKey, KEY_EXPIRES_AT_MS), 0L)
        val refreshToken = kv.decodeString(namespacedKey(storageKey, KEY_REFRESH_TOKEN))
        val scope = kv.decodeString(namespacedKey(storageKey, KEY_SCOPE))

        val uk = kv.decodeLong(namespacedKey(storageKey, KEY_UK), -1L).takeIf { it > 0 }
        val netdiskName = kv.decodeString(namespacedKey(storageKey, KEY_NETDISK_NAME))
        val avatarUrl = kv.decodeString(namespacedKey(storageKey, KEY_AVATAR_URL))
        val updatedAtMs = kv.decodeLong(namespacedKey(storageKey, KEY_UPDATED_AT_MS), 0L)

        return AuthState(
            accessToken = accessToken,
            expiresAtMs = expiresAtMs,
            refreshToken = refreshToken,
            scope = scope,
            uk = uk,
            netdiskName = netdiskName,
            avatarUrl = avatarUrl,
            updatedAtMs = updatedAtMs,
        )
    }

    fun writeTokens(
        storageKey: String,
        accessToken: String?,
        expiresAtMs: Long,
        refreshToken: String?,
        scope: String?,
        updatedAtMs: Long = System.currentTimeMillis()
    ) {
        val kv = mmkv()
        accessToken?.let { kv.encode(namespacedKey(storageKey, KEY_ACCESS_TOKEN), it) }
        kv.encode(namespacedKey(storageKey, KEY_EXPIRES_AT_MS), expiresAtMs)
        refreshToken?.let { kv.encode(namespacedKey(storageKey, KEY_REFRESH_TOKEN), it) }
        scope?.let { kv.encode(namespacedKey(storageKey, KEY_SCOPE), it) }
        kv.encode(namespacedKey(storageKey, KEY_UPDATED_AT_MS), updatedAtMs)
    }

    fun writeProfile(
        storageKey: String,
        uk: Long?,
        netdiskName: String?,
        avatarUrl: String?,
        updatedAtMs: Long = System.currentTimeMillis()
    ) {
        val kv = mmkv()
        uk?.let { kv.encode(namespacedKey(storageKey, KEY_UK), it) }
        netdiskName?.let { kv.encode(namespacedKey(storageKey, KEY_NETDISK_NAME), it) }
        avatarUrl?.let { kv.encode(namespacedKey(storageKey, KEY_AVATAR_URL), it) }
        kv.encode(namespacedKey(storageKey, KEY_UPDATED_AT_MS), updatedAtMs)
    }

    fun clear(storageKey: String) {
        val kv = mmkv()
        kv.removeValueForKey(namespacedKey(storageKey, KEY_ACCESS_TOKEN))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_EXPIRES_AT_MS))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_REFRESH_TOKEN))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_SCOPE))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_UK))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_NETDISK_NAME))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_AVATAR_URL))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_UPDATED_AT_MS))
    }

    private fun mmkv(): MMKV = MMKV.mmkvWithID(MMKV_ID)

    private fun namespacedKey(
        storageKey: String,
        fieldKey: String
    ): String = "$storageKey.$fieldKey"
}
