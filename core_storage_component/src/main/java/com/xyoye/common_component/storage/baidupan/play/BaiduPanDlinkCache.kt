package com.xyoye.common_component.storage.baidupan.play

import java.util.concurrent.ConcurrentHashMap

/**
 * 百度网盘 dlink 缓存（按 fsId）。
 *
 * - 优先使用未过期缓存
 * - 强制刷新失败时允许回退旧值（即便已过期）
 */
class BaiduPanDlinkCache {
    data class Entry(
        val fsId: Long,
        val dlink: String,
        val expiresAtMs: Long,
        val contentLength: Long
    ) {
        fun isValid(nowMs: Long): Boolean = dlink.isNotBlank() && nowMs < expiresAtMs
    }

    private val cache: MutableMap<Long, Entry> = ConcurrentHashMap()

    fun getValid(
        fsId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Entry? = cache[fsId]?.takeIf { it.isValid(nowMs) }

    fun getAny(fsId: Long): Entry? = cache[fsId]

    fun put(entry: Entry) {
        if (entry.fsId <= 0 || entry.dlink.isBlank()) {
            return
        }
        cache[entry.fsId] = entry
    }

    fun clear(fsId: Long) {
        cache.remove(fsId)
    }

    suspend fun resolve(
        fsId: Long,
        forceRefresh: Boolean,
        loader: suspend () -> Entry
    ): Entry {
        val nowMs = System.currentTimeMillis()
        val cached = cache[fsId]

        if (!forceRefresh) {
            cached?.takeIf { it.isValid(nowMs) }?.let { return it }
        }

        return runCatching {
            loader.invoke()
        }.onSuccess { fresh ->
            put(fresh)
        }.getOrElse { e ->
            cached?.let { return it }
            throw e
        }
    }
}

