package com.xyoye.common_component.bilibili

import com.xyoye.common_component.bilibili.cdn.BilibiliCdnService
import com.tencent.mmkv.MMKV
import com.xyoye.data_component.entity.MediaLibraryEntity

/**
 * Bilibili 播放偏好存储（MMKV）。
 *
 * 设计说明：
 * - 按媒体库唯一键隔离：storageKey = "${mediaType.value}:${url}"
 * - 避免新增媒体库时无法拿到自增 id 的问题
 * - 未来如需要迁移到按 id 存储，可通过一层映射兼容读取旧 key
 */
object BilibiliPlaybackPreferencesStore {
    private const val MMKV_ID = "bilibili_playback_preferences"

    private const val KEY_PLAY_MODE = "play_mode"
    private const val KEY_QUALITY_QN = "quality_qn"
    private const val KEY_VIDEO_CODEC = "video_codec"
    private const val KEY_ALLOW_4K = "allow_4k"
    private const val KEY_CDN_SERVICE = "cdn_service"

    fun storageKey(library: MediaLibraryEntity): String =
        "${library.mediaType.value}:${library.url.trim().removeSuffix("/")}"

    fun read(library: MediaLibraryEntity): BilibiliPlaybackPreferences = read(storageKey(library))

    fun write(
        library: MediaLibraryEntity,
        preferences: BilibiliPlaybackPreferences
    ) = write(storageKey(library), preferences)

    fun clear(library: MediaLibraryEntity) = clear(storageKey(library))

    fun read(storageKey: String): BilibiliPlaybackPreferences {
        val kv = mmkv()
        val mode =
            runCatching {
                kv.decodeString(namespacedKey(storageKey, KEY_PLAY_MODE))?.let { BilibiliPlayMode.valueOf(it) }
            }.getOrNull() ?: BilibiliPlayMode.AUTO
        val qn =
            kv.decodeInt(
                namespacedKey(storageKey, KEY_QUALITY_QN),
                BilibiliQuality.QN_720P.qn,
            )
        val codec =
            runCatching {
                kv.decodeString(namespacedKey(storageKey, KEY_VIDEO_CODEC))?.let { BilibiliVideoCodec.valueOf(it) }
            }.getOrNull() ?: BilibiliVideoCodec.AVC
        val allow4k = kv.decodeBool(namespacedKey(storageKey, KEY_ALLOW_4K), false)
        val cdnService =
            runCatching {
                kv.decodeString(namespacedKey(storageKey, KEY_CDN_SERVICE))?.let { BilibiliCdnService.valueOf(it) }
            }.getOrNull() ?: BilibiliCdnService.AUTO

        return BilibiliPlaybackPreferences(
            playMode = mode,
            preferredQualityQn = qn,
            preferredVideoCodec = codec,
            allow4k = allow4k,
            cdnService = cdnService,
        )
    }

    fun write(
        storageKey: String,
        preferences: BilibiliPlaybackPreferences
    ) {
        val kv = mmkv()
        kv.encode(namespacedKey(storageKey, KEY_PLAY_MODE), preferences.playMode.name)
        kv.encode(namespacedKey(storageKey, KEY_QUALITY_QN), preferences.preferredQualityQn)
        kv.encode(namespacedKey(storageKey, KEY_VIDEO_CODEC), preferences.preferredVideoCodec.name)
        kv.encode(namespacedKey(storageKey, KEY_ALLOW_4K), preferences.allow4k)
        kv.encode(namespacedKey(storageKey, KEY_CDN_SERVICE), preferences.cdnService.name)
    }

    fun clear(storageKey: String) {
        val kv = mmkv()
        kv.removeValueForKey(namespacedKey(storageKey, KEY_PLAY_MODE))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_QUALITY_QN))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_VIDEO_CODEC))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_ALLOW_4K))
        kv.removeValueForKey(namespacedKey(storageKey, KEY_CDN_SERVICE))
    }

    private fun mmkv(): MMKV = MMKV.mmkvWithID(MMKV_ID)

    private fun namespacedKey(
        storageKey: String,
        fieldKey: String
    ): String = "$storageKey.$fieldKey"
}
