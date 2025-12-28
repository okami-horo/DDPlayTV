package com.xyoye.common_component.bilibili.auth

import com.squareup.moshi.Types
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.utils.JsonHelper
import com.xyoye.data_component.data.bilibili.BilibiliPersistedCookie
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Bilibili 可持久化 CookieJar（按媒体库 storageKey 隔离）。
 *
 * 注意：
 * - 播放器侧（非 OkHttp）需要 Cookie Header 时，可通过 [exportCookieHeader] 导出。
 * - 本实现以“稳定可用”为优先：按 host 存储与回放 Cookie，并在读取时清理过期项。
 */
class BilibiliCookieJarStore(
    private val storageKey: String
) : CookieJar {
    private val lock = Any()

    private val kv: MMKV by lazy {
        MMKV.mmkvWithID("bilibili_cookie_jar_${storageKey.toMd5String()}")
    }

    private val adapter by lazy {
        val mapType =
            Types.newParameterizedType(
                Map::class.java,
                String::class.java,
                Types.newParameterizedType(List::class.java, BilibiliPersistedCookie::class.java),
            )
        JsonHelper.MO_SHI.adapter<Map<String, List<BilibiliPersistedCookie>>>(mapType)
    }

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>
    ) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val all = readAll().toMutableMap()
            val existing = all[url.host].orEmpty().toMutableList()

            cookies
                .filterNot { it.expiresAt < now }
                .map { it.toPersistedCookie() }
                .forEach { incoming ->
                    val index =
                        existing.indexOfFirst {
                            it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path
                        }
                    if (index >= 0) {
                        existing[index] = incoming
                    } else {
                        existing.add(incoming)
                    }
                }

            all[url.host] = existing
            writeAll(all)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val all = readAll().toMutableMap()
            if (all.isEmpty()) {
                return@synchronized emptyList()
            }

            // Clear expired items globally to avoid leaking cookies across different host buckets.
            var changed = false
            val cleanedAll =
                all.mapValues { (_, list) ->
                    val filtered = list.filterNot { it.expiresAt < now }
                    if (filtered.size != list.size) changed = true
                    filtered
                }
            if (changed) {
                writeAll(cleanedAll)
            }

            // Do not restrict by host: match cookies by domain/path for current request.
            data class CookieKey(
                val name: String,
                val domain: String,
                val path: String,
                val hostOnly: Boolean,
            )

            cleanedAll
                .values
                .flatten()
                .groupBy { CookieKey(it.name, it.domain, it.path, it.hostOnly) }
                .values
                .mapNotNull { candidates -> candidates.maxByOrNull { it.expiresAt }?.toCookie() }
                .filter { cookie -> cookie.matches(url) }
        }

    fun clear() {
        synchronized(lock) {
            kv.clearAll()
        }
    }

    fun isLoginCookiePresent(): Boolean =
        hasCookieName(
            name = "SESSDATA",
            domainSuffix = "bilibili.com",
        )

    fun exportCookieHeader(
        domainSuffix: String = "bilibili.com"
    ): String? =
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val cookies =
                readAll()
                    .values
                    .flatten()
                    .filterNot { it.expiresAt < now }
                    .filter { it.domain.endsWith(domainSuffix, ignoreCase = true) }
                    .distinctBy { it.name }
                    .sortedBy { it.name }

            if (cookies.isEmpty()) return@synchronized null
            cookies.joinToString(separator = "; ") { "${it.name}=${it.value}" }
        }

    private fun hasCookieName(
        name: String,
        domainSuffix: String
    ): Boolean =
        synchronized(lock) {
            val now = System.currentTimeMillis()
            readAll()
                .values
                .flatten()
                .any {
                    it.name == name &&
                        it.expiresAt >= now &&
                        it.domain.endsWith(domainSuffix, ignoreCase = true)
                }
        }

    private fun readAll(): Map<String, List<BilibiliPersistedCookie>> {
        val raw = kv.decodeString(KEY_ALL).orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return runCatching { adapter.fromJson(raw) }.getOrNull().orEmpty()
    }

    private fun writeAll(all: Map<String, List<BilibiliPersistedCookie>>) {
        val raw = adapter.toJson(all)
        kv.encode(KEY_ALL, raw)
    }

    private fun Cookie.toPersistedCookie(): BilibiliPersistedCookie =
        BilibiliPersistedCookie(
            name = name,
            value = value,
            expiresAt = expiresAt,
            domain = domain,
            path = path,
            secure = secure,
            httpOnly = httpOnly,
            hostOnly = hostOnly,
            persistent = persistent,
        )

    private fun BilibiliPersistedCookie.toCookie(): Cookie? =
        runCatching {
            Cookie
                .Builder()
                .name(name)
                .value(value)
                .expiresAt(expiresAt)
                .path(path)
                .apply {
                    if (hostOnly) {
                        hostOnlyDomain(domain)
                    } else {
                        domain(domain)
                    }
                    if (secure) {
                        secure()
                    }
                    if (httpOnly) {
                        httpOnly()
                    }
                }.build()
        }.getOrNull()

    private companion object {
        private const val KEY_ALL = "cookies_all"
    }
}
