package com.xyoye.common_component.storage.open115.auth

import com.tencent.mmkv.MMKV
import com.xyoye.data_component.entity.MediaLibraryEntity

/**
 * 115 Open 授权态存储（按媒体库 storageKey 隔离）。
 *
 * 设计说明：
 * - 按媒体库唯一键隔离：storageKey = "${mediaType.value}:${url.trim().removeSuffix("/")}"
 * - 避免新增媒体库时无法拿到自增 id 的问题
 */
object Open115AuthStore {
    private const val MMKV_ID = "open115_auth_store"

    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
    private const val KEY_REFRESH_TOKEN = "refresh_token"

    private const val KEY_UID = "uid"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_AVATAR_URL = "avatar_url"

    private const val KEY_UPDATED_AT_MS = "updated_at_ms"

    data class AuthState(
        val accessToken: String? = null,
        val expiresAtMs: Long = 0L,
        val refreshToken: String? = null,
        val uid: String? = null,
        val userName: String? = null,
        val avatarUrl: String? = null,
        val updatedAtMs: Long = 0L
    ) {
        fun isAuthorized(): Boolean = refreshToken.isNullOrBlank().not() && uid.isNullOrBlank().not()
    }

    fun storageKey(library: MediaLibraryEntity): String = "${library.mediaType.value}:${library.url.trim().removeSuffix("/")}"

    fun read(storageKey: String): AuthState {
        val kv = mmkv()
        val accessToken = kv.decodeString(namespacedKey(storageKey, KEY_ACCESS_TOKEN))
        val expiresAtMs = kv.decodeLong(namespacedKey(storageKey, KEY_EXPIRES_AT_MS), 0L)
        val refreshToken = kv.decodeString(namespacedKey(storageKey, KEY_REFRESH_TOKEN))

        val uid = kv.decodeString(namespacedKey(storageKey, KEY_UID))
        val userName = kv.decodeString(namespacedKey(storageKey, KEY_USER_NAME))
        val avatarUrl = kv.decodeString(namespacedKey(storageKey, KEY_AVATAR_URL))
        val updatedAtMs = kv.decodeLong(namespacedKey(storageKey, KEY_UPDATED_AT_MS), 0L)

        return AuthState(
            accessToken = accessToken,
            expiresAtMs = expiresAtMs,
            refreshToken = refreshToken,
            uid = uid,
            userName = userName,
            avatarUrl = avatarUrl,
            updatedAtMs = updatedAtMs,
        )
    }

    fun writeTokens(
        storageKey: String,
        accessToken: String?,
        expiresAtMs: Long,
        refreshToken: String?,
        updatedAtMs: Long = System.currentTimeMillis()
    ) {
        val kv = mmkv()
        accessToken?.let { kv.encode(namespacedKey(storageKey, KEY_ACCESS_TOKEN), it) }
        kv.encode(namespacedKey(storageKey, KEY_EXPIRES_AT_MS), expiresAtMs)
        refreshToken?.let { kv.encode(namespacedKey(storageKey, KEY_REFRESH_TOKEN), it) }
        kv.encode(namespacedKey(storageKey, KEY_UPDATED_AT_MS), updatedAtMs)
    }

    fun writeProfile(
        storageKey: String,
        uid: String?,
        userName: String?,
        avatarUrl: String?,
        updatedAtMs: Long = System.currentTimeMillis()
    ) {
        val kv = mmkv()
        uid?.let { kv.encode(namespacedKey(storageKey, KEY_UID), it) }
        userName?.let { kv.encode(namespacedKey(storageKey, KEY_USER_NAME), it) }
        avatarUrl?.let { kv.encode(namespacedKey(storageKey, KEY_AVATAR_URL), it) }
        kv.encode(namespacedKey(storageKey, KEY_UPDATED_AT_MS), updatedAtMs)
    }

    fun clear(storageKey: String) {
        val kv = mmkv()
        kv.removeValueForKey(namespacedKey(storageKey, KEY_ACCESS_TOKEN))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_EXPIRES_AT_MS))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_REFRESH_TOKEN))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_UID))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_USER_NAME))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_AVATAR_URL))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_UPDATED_AT_MS))
    }

    private fun mmkv(): MMKV = MMKV.mmkvWithID(MMKV_ID)

    private fun namespacedKey(
        storageKey: String,
        fieldKey: String
    ): String = "$storageKey.$fieldKey"
}

