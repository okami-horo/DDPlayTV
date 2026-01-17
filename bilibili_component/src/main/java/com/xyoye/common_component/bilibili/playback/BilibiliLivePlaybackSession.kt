package com.xyoye.common_component.bilibili.playback

import com.xyoye.common_component.bilibili.error.BilibiliException
import com.xyoye.common_component.bilibili.repository.BilibiliRepository
import com.xyoye.data_component.data.bilibili.BilibiliLivePlayUrlData
import com.xyoye.data_component.data.bilibili.BilibiliLivePlayUrlDurl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

class BilibiliLivePlaybackSession(
    val storageId: Int,
    val uniqueKey: String,
    private val repository: BilibiliRepository,
    private val roomId: Long
) {
    private val cdnBlacklistTtlMs = TimeUnit.MINUTES.toMillis(10)
    private val cdnHostBlacklist = LinkedHashMap<String, Long>()

    private val expiresMarginMs = TimeUnit.MINUTES.toMillis(1)

    private var resolvedRoomId: Long? = null
    private var urlCandidates: List<String> = emptyList()
    private var selectedIndex: Int = -1
    private var selectedExpiresAtMs: Long? = null

    suspend fun prepare(): Result<String> =
        runCatching {
            refreshPlayUrl()
            selectFirstCandidateOrThrow()
        }

    suspend fun recover(failure: BilibiliPlaybackSession.FailureContext): Result<String> =
        runCatching {
            cleanupBlacklistIfNeeded()

            if (shouldForceRefresh(failure) || isSelectedUrlExpiredSoon()) {
                failure.failingUrl?.let { blacklistHost(it) }
                refreshPlayUrl()
                return@runCatching selectFirstCandidateOrThrow()
            }

            failure.failingUrl?.let { blacklistHost(it) }
            nextCandidateOrRefresh()
        }

    private fun shouldForceRefresh(failure: BilibiliPlaybackSession.FailureContext): Boolean {
        val code = failure.httpResponseCode ?: return false
        return code == 403 || code == 404 || code == 410
    }

    private fun isSelectedUrlExpiredSoon(): Boolean {
        val expiresAtMs = selectedExpiresAtMs ?: return false
        return System.currentTimeMillis() >= (expiresAtMs - expiresMarginMs)
    }

    private suspend fun resolveRoomId(): Long {
        val resolved = resolvedRoomId
        if (resolved != null && resolved > 0) return resolved

        val info = repository.liveRoomInfo(roomId).getOrThrow()
        val cid = info.roomId.takeIf { it > 0 } ?: roomId
        resolvedRoomId = cid
        return cid
    }

    private suspend fun refreshPlayUrl() {
        val cid = resolveRoomId()
        val playUrl = repository.livePlayUrl(cid).getOrThrow()
        urlCandidates = buildUrlCandidates(playUrl)
        selectedIndex = -1
        selectedExpiresAtMs = null

        if (urlCandidates.isEmpty()) {
            throw BilibiliException.from(-1, "取流失败")
        }
    }

    private fun buildUrlCandidates(data: BilibiliLivePlayUrlData): List<String> {
        if (data.durl.isEmpty()) return emptyList()

        val ordered =
            data.durl.sortedWith(
                compareBy<BilibiliLivePlayUrlDurl> { it.order }
                    .thenBy { it.url }
            )

        val urls = LinkedHashSet<String>()
        ordered.forEach { durl ->
            durl.url.trim().takeIf { it.isNotEmpty() }?.let(urls::add)
            durl.backupUrl.forEach { backup ->
                backup.trim().takeIf { it.isNotEmpty() }?.let(urls::add)
            }
        }
        return urls.toList()
    }

    private fun selectFirstCandidateOrThrow(): String {
        val candidate =
            firstCandidateOrNull()
                ?: throw BilibiliException.from(-1, "取流失败")
        selectedIndex = urlCandidates.indexOf(candidate).takeIf { it >= 0 } ?: 0
        selectedExpiresAtMs = parseExpiresAtMs(candidate)
        return candidate
    }

    private suspend fun nextCandidateOrRefresh(): String {
        val candidate = nextCandidateOrNull()
        if (candidate != null) return candidate

        refreshPlayUrl()
        return selectFirstCandidateOrThrow()
    }

    private fun firstCandidateOrNull(): String? {
        if (urlCandidates.isEmpty()) return null

        val blacklist = cdnHostBlacklist.keys
        urlCandidates.forEach { url ->
            val host = url.toHttpUrlOrNull()?.host ?: return@forEach
            if (!blacklist.contains(host)) {
                return url
            }
        }

        return urlCandidates.firstOrNull()
    }

    private fun nextCandidateOrNull(): String? {
        if (urlCandidates.isEmpty()) return null
        if (selectedIndex < 0) {
            return selectFirstCandidateOrThrow()
        }

        val blacklist = cdnHostBlacklist.keys
        val startIndex = selectedIndex.coerceIn(0, urlCandidates.lastIndex)
        for (offset in 1..urlCandidates.size) {
            val index = (startIndex + offset) % urlCandidates.size
            val url = urlCandidates[index]
            val host = url.toHttpUrlOrNull()?.host ?: continue
            if (!blacklist.contains(host)) {
                selectedIndex = index
                selectedExpiresAtMs = parseExpiresAtMs(url)
                return url
            }
        }
        return null
    }

    private fun parseExpiresAtMs(url: String): Long? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val expires = httpUrl.queryParameter("expires") ?: httpUrl.queryParameter("expire") ?: return null
        val expiresValue = expires.toLongOrNull() ?: return null
        if (expiresValue <= 0) return null

        return if (expiresValue > 10_000_000_000L) {
            expiresValue
        } else {
            expiresValue * 1000
        }
    }

    private fun blacklistHost(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        val expiry = System.currentTimeMillis() + cdnBlacklistTtlMs
        val existed = cdnHostBlacklist.containsKey(host)
        cdnHostBlacklist[host] = expiry
        return !existed
    }

    private fun cleanupBlacklistIfNeeded() {
        if (cdnHostBlacklist.isEmpty()) return

        val now = System.currentTimeMillis()
        val iterator = cdnHostBlacklist.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value <= now) {
                iterator.remove()
            }
        }
    }
}
