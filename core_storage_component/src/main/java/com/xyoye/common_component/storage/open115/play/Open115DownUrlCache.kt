package com.xyoye.common_component.storage.open115.play

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 115 Open downurl 缓存（按 fid）。
 *
 * - 优先使用未过期缓存（短 TTL）
 * - 强制刷新失败时允许回退旧值（即便已过期）
 */
class Open115DownUrlCache {
    data class Entry(
        val fid: String,
        val pickCode: String,
        val url: String,
        val userAgent: String,
        val fileSize: Long,
        val updatedAtMs: Long
    ) {
        fun isValid(
            nowMs: Long,
            ttlMs: Long
        ): Boolean =
            url.isNotBlank() &&
                userAgent.isNotBlank() &&
                updatedAtMs > 0L &&
                nowMs < updatedAtMs + ttlMs
    }

    private val cache: MutableMap<String, Entry> = ConcurrentHashMap()

    fun getValid(
        fid: String,
        nowMs: Long = System.currentTimeMillis()
    ): Entry? = cache[fid]?.takeIf { it.isValid(nowMs, DEFAULT_TTL_MS) }

    fun getAny(fid: String): Entry? = cache[fid]

    fun put(entry: Entry) {
        if (entry.fid.isBlank() || entry.url.isBlank()) {
            return
        }
        cache[entry.fid] = entry
    }

    fun clear(fid: String) {
        cache.remove(fid)
    }

    suspend fun resolve(
        fid: String,
        forceRefresh: Boolean,
        loader: suspend () -> Entry
    ): Entry {
        val nowMs = System.currentTimeMillis()
        val cached = cache[fid]

        if (!forceRefresh) {
            cached?.takeIf { it.isValid(nowMs, DEFAULT_TTL_MS) }?.let { return it }
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

    companion object {
        private val DEFAULT_TTL_MS: Long = TimeUnit.MINUTES.toMillis(2)
    }
}

