package com.xyoye.common_component.bilibili.history

import com.tencent.mmkv.MMKV
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.utils.JsonHelper
import com.xyoye.data_component.data.bilibili.BilibiliHistoryCursorData

/**
 * Bilibili 历史记录首屏缓存（按媒体库 storageKey 隔离）。
 *
 * 目标：
 * - 提升“进入历史目录首屏可见”的命中率（SC-001）
 * - 刷新时仍可保留旧数据展示（由 UI 层控制）
 */
class BilibiliHistoryCacheStore(
    storageKey: String
) {
    private val kv: MMKV = MMKV.mmkvWithID("bilibili_history_cache_${storageKey.toMd5String()}")

    fun readFirstPageOrNull(
        type: String,
        maxAgeMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): BilibiliHistoryCursorData? {
        val key = normalizeType(type)
        val savedAt = kv.decodeLong(keyFirstPageAt(key), 0L)
        if (savedAt <= 0L || nowMs - savedAt > maxAgeMs) {
            return null
        }
        val raw = kv.decodeString(keyFirstPage(key)).orEmpty()
        return JsonHelper.parseJson<BilibiliHistoryCursorData>(raw)
    }

    fun writeFirstPage(
        type: String,
        data: BilibiliHistoryCursorData,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val raw = JsonHelper.toJson(data) ?: return
        val key = normalizeType(type)
        kv.encode(keyFirstPage(key), raw)
        kv.encode(keyFirstPageAt(key), nowMs)
    }

    fun clear() {
        kv.clearAll()
    }

    private companion object {
        private const val KEY_FIRST_PAGE_PREFIX = "first_page"
        private const val KEY_FIRST_PAGE_AT_PREFIX = "first_page_at"

        private fun normalizeType(type: String): String = type.trim().ifBlank { "archive" }

        private fun keyFirstPage(type: String): String = "$KEY_FIRST_PAGE_PREFIX.$type"

        private fun keyFirstPageAt(type: String): String = "$KEY_FIRST_PAGE_AT_PREFIX.$type"
    }
}
