package com.xyoye.common_component.bilibili.cdn

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Bilibili CDN 策略（Phase 1）：
 * - baseUrl + backupUrl 合并、去重、排序
 * - 可选的 host 重写（保留原始 URL 作为回退）
 *
 * 说明：
 * - 这里不做“按地域/网络环境”的复杂判定，优先提供稳定的排序与重写能力
 * - 更复杂的黑名单/失败统计/会话化重建在后续 Phase 2 引入
 */
object BilibiliCdnStrategy {
    data class Options(
        val hostOverride: String? = null,
    )

    fun resolveUrls(
        baseUrl: String,
        backupUrls: List<String>,
        options: Options = Options(),
    ): List<String> {
        val candidates =
            buildList {
                if (baseUrl.isNotBlank()) add(baseUrl)
                backupUrls.filter { it.isNotBlank() }.forEach { add(it) }
            }.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

        if (candidates.isEmpty()) return emptyList()

        val ranked =
            candidates
                .mapIndexed { index, url -> Candidate(url = url, score = score(url), index = index) }
                .sortedWith(compareBy<Candidate> { it.score }.thenBy { it.index })
                .map { it.url }

        val hostOverride = parseHostOverride(options.hostOverride)
        if (hostOverride == null) {
            return ranked
        }

        val rewritten =
            ranked
                .mapNotNull { rewriteOrNull(it, hostOverride) }
                .distinct()

        val merged = LinkedHashSet<String>(rewritten.size + ranked.size)
        rewritten.forEach { merged.add(it) }
        ranked.forEach { merged.add(it) }
        return merged.toList()
    }

    fun resolvePrimaryUrl(
        baseUrl: String,
        backupUrls: List<String>,
        options: Options = Options(),
    ): String? = resolveUrls(baseUrl, backupUrls, options).firstOrNull()

    private data class Candidate(
        val url: String,
        val score: Int,
        val index: Int,
    )

    private data class HostOverride(
        val scheme: String,
        val host: String,
        val port: Int?,
    )

    private fun score(url: String): Int {
        val httpUrl = url.toHttpUrlOrNull() ?: return Int.MAX_VALUE

        var score = 0
        if (httpUrl.scheme.equals("http", ignoreCase = true)) {
            score += 10
        }

        val host = httpUrl.host.lowercase()
        if (host.contains("-302")) {
            score += 100
        }

        val os = httpUrl.queryParameter("os")?.lowercase()
        if (os == "mcdn" || host.contains(".mcdn.") || host.contains("mcdn.")) {
            score += 200
        }

        return score
    }

    private fun rewriteOrNull(
        url: String,
        override: HostOverride,
    ): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val builder =
            httpUrl
                .newBuilder()
                .scheme(override.scheme)
                .host(override.host)

        override.port?.let { builder.port(it) }

        return builder.build().toString()
    }

    private fun parseHostOverride(raw: String?): HostOverride? {
        val input = raw?.trim().orEmpty()
        if (input.isBlank()) return null

        val normalized =
            if (input.contains("://")) {
                input
            } else {
                "https://$input"
            }

        val url = normalized.toHttpUrlOrNull() ?: return null
        return HostOverride(
            scheme = url.scheme,
            host = url.host,
            port = url.port.takeIf { it != defaultPort(url.scheme) },
        )
    }

    private fun defaultPort(scheme: String): Int =
        if (scheme.equals("http", ignoreCase = true)) {
            80
        } else {
            443
        }
}
