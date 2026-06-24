package com.xyoye.common_component.utils.danmu.cache

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.tencent.mmkv.MMKV
import java.util.concurrent.TimeUnit

interface DanmuMatchCacheStore {
    fun getSuccess(hash: String, fileSize: Long): CachedMatch?

    fun putSuccess(hash: String, fileSize: Long, match: CachedMatch)

    fun hasRecentFail(hash: String, fileSize: Long): Boolean

    fun putFail(hash: String, fileSize: Long)
}

object DanmuMatchCache : DanmuMatchCacheStore {
    private const val MMKV_ID = "danmu_match_cache"
    private const val SUCCESS_PREFIX = "danmu_match_success"
    private const val FAIL_PREFIX = "danmu_match_fail"

    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }
    private val adapter by lazy { Moshi.Builder().build().adapter(CachedMatch::class.java) }

    override fun getSuccess(hash: String, fileSize: Long): CachedMatch? {
        val key = successKey(hash, fileSize)
        val raw = mmkv.decodeString(key) ?: return null
        val cached = runCatching { adapter.fromJson(raw) }.getOrNull() ?: return null
        if (System.currentTimeMillis() - cached.cachedAt > TimeUnit.DAYS.toMillis(30)) {
            mmkv.removeValueForKey(key)
            return null
        }
        return cached
    }

    override fun putSuccess(hash: String, fileSize: Long, match: CachedMatch) {
        mmkv.encode(successKey(hash, fileSize), adapter.toJson(match))
    }

    override fun hasRecentFail(hash: String, fileSize: Long): Boolean {
        val key = failKey(hash, fileSize)
        val cachedAt = mmkv.decodeLong(key, 0L)
        if (cachedAt <= 0L) return false
        if (System.currentTimeMillis() - cachedAt > TimeUnit.DAYS.toMillis(1)) {
            mmkv.removeValueForKey(key)
            return false
        }
        return true
    }

    override fun putFail(hash: String, fileSize: Long) {
        mmkv.encode(failKey(hash, fileSize), System.currentTimeMillis())
    }

    private fun successKey(hash: String, fileSize: Long): String = "${SUCCESS_PREFIX}_${hash}_${fileSize}"

    private fun failKey(hash: String, fileSize: Long): String = "${FAIL_PREFIX}_${hash}_${fileSize}"
}

@JsonClass(generateAdapter = true)
data class CachedMatch(
    val episodeId: Long,
    val animeId: Long,
    val animeTitle: String,
    val episodeTitle: String,
    val shift: Double,
    val cachedAt: Long,
)
