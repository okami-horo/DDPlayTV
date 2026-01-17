package com.xyoye.common_component.bilibili

import com.tencent.mmkv.MMKV
import com.xyoye.data_component.entity.MediaLibraryEntity

/**
 * Bilibili 弹幕屏蔽偏好存储（MMKV）。
 *
 * 设计说明：
 * - 与 [BilibiliPlaybackPreferencesStore] 相同：按媒体库唯一键隔离 storageKey
 * - 仅在本地生效，不会同步修改用户 B 站账号设置
 */
object BilibiliDanmakuBlockPreferencesStore {
    private const val MMKV_ID = "bilibili_danmaku_block_preferences"

    private const val KEY_AI_SWITCH = "ai_switch"
    private const val KEY_AI_LEVEL = "ai_level"

    fun read(library: MediaLibraryEntity): BilibiliDanmakuBlockPreferences = read(storageKey(library))

    fun write(
        library: MediaLibraryEntity,
        preferences: BilibiliDanmakuBlockPreferences
    ) = write(storageKey(library), preferences)

    fun clear(library: MediaLibraryEntity) = clear(storageKey(library))

    fun read(storageKey: String): BilibiliDanmakuBlockPreferences {
        val kv = mmkv()
        val aiSwitch = kv.decodeBool(namespacedKey(storageKey, KEY_AI_SWITCH), false)
        val aiLevel = kv.decodeInt(namespacedKey(storageKey, KEY_AI_LEVEL), 0).coerceIn(0, 10)
        return BilibiliDanmakuBlockPreferences(
            aiSwitch = aiSwitch,
            aiLevel = aiLevel,
        )
    }

    fun write(
        storageKey: String,
        preferences: BilibiliDanmakuBlockPreferences
    ) {
        val kv = mmkv()
        kv.encode(namespacedKey(storageKey, KEY_AI_SWITCH), preferences.aiSwitch)
        kv.encode(namespacedKey(storageKey, KEY_AI_LEVEL), preferences.aiLevel.coerceIn(0, 10))
    }

    fun clear(storageKey: String) {
        val kv = mmkv()
        kv.removeValueForKey(namespacedKey(storageKey, KEY_AI_SWITCH))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_AI_LEVEL))
    }

    private fun storageKey(library: MediaLibraryEntity): String = BilibiliPlaybackPreferencesStore.storageKey(library)

    private fun mmkv(): MMKV = MMKV.mmkvWithID(MMKV_ID)

    private fun namespacedKey(
        storageKey: String,
        fieldKey: String
    ): String = "$storageKey.$fieldKey"
}
