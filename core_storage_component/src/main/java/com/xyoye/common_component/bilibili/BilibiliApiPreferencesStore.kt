package com.xyoye.common_component.bilibili

import com.tencent.mmkv.MMKV
import com.xyoye.data_component.entity.MediaLibraryEntity

/**
 * Bilibili API 类型偏好存储（MMKV）。
 *
 * 设计说明：
 * - 与 [BilibiliPlaybackPreferencesStore] 相同：按媒体库唯一键隔离 storageKey
 * - 用于切换 Web API / TV 客户端 API（影响登录与取流链路）
 */
object BilibiliApiPreferencesStore {
    private const val MMKV_ID = "bilibili_api_preferences"

    private const val KEY_API_TYPE = "api_type"

    fun read(library: MediaLibraryEntity): BilibiliApiPreferences = read(storageKey(library))

    fun write(
        library: MediaLibraryEntity,
        preferences: BilibiliApiPreferences,
    ) = write(storageKey(library), preferences)

    fun clear(library: MediaLibraryEntity) = clear(storageKey(library))

    fun read(storageKey: String): BilibiliApiPreferences {
        val kv = mmkv()
        val type =
            runCatching {
                kv.decodeString(namespacedKey(storageKey, KEY_API_TYPE))
                    ?.let { BilibiliApiType.valueOf(it) }
            }.getOrNull() ?: BilibiliApiType.WEB
        return BilibiliApiPreferences(apiType = type)
    }

    fun write(
        storageKey: String,
        preferences: BilibiliApiPreferences,
    ) {
        val kv = mmkv()
        kv.encode(namespacedKey(storageKey, KEY_API_TYPE), preferences.apiType.name)
    }

    fun clear(storageKey: String) {
        val kv = mmkv()
        kv.removeValueForKey(namespacedKey(storageKey, KEY_API_TYPE))
    }

    private fun storageKey(library: MediaLibraryEntity): String =
        BilibiliPlaybackPreferencesStore.storageKey(library)

    private fun mmkv(): MMKV = MMKV.mmkvWithID(MMKV_ID)

    private fun namespacedKey(
        storageKey: String,
        fieldKey: String,
    ): String = "$storageKey.$fieldKey"
}

