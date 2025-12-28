package com.xyoye.common_component.bilibili.auth

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Types
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.utils.JsonHelper
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
                Types.newParameterizedType(List::class.java, PersistedCookie::class.java),
            )
        JsonHelper.MO_SHI.adapter<Map<String, List<PersistedCookie>>>(mapType)
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
                .map { PersistedCookie.from(it) }
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
            val current = all[url.host].orEmpty()
            if (current.isEmpty()) {
                return@synchronized emptyList()
            }

            val valid =
                current
                    .filterNot { it.expiresAt < now }
                    .mapNotNull { it.toCookie() }
                    .filter { cookie ->
                        cookie.matches(url)
                    }

            if (valid.size != current.size) {
                all[url.host] = current.filterNot { it.expiresAt < now }
                writeAll(all)
            }

            valid
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

    private fun readAll(): Map<String, List<PersistedCookie>> {
        val raw = kv.decodeString(KEY_ALL).orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return runCatching { adapter.fromJson(raw) }.getOrNull().orEmpty()
    }

    private fun writeAll(all: Map<String, List<PersistedCookie>>) {
        val raw = adapter.toJson(all)
        kv.encode(KEY_ALL, raw)
    }

    @JsonClass(generateAdapter = true)
    data class PersistedCookie(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
        val persistent: Boolean,
    ) {
        companion object {
            fun from(cookie: Cookie): PersistedCookie =
                PersistedCookie(
                    name = cookie.name,
                    value = cookie.value,
                    expiresAt = cookie.expiresAt,
                    domain = cookie.domain,
                    path = cookie.path,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    hostOnly = cookie.hostOnly,
                    persistent = cookie.persistent,
                )
        }

        fun toCookie(): Cookie? =
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
    }

    private companion object {
        private const val KEY_ALL = "cookies_all"
    }
}

