package com.xyoye.common_component.log.privacy

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * 统一脱敏工具：用于日志与异常上报上下文的默认脱敏。
 *
 * 目标：
 * - 默认不输出 token/cookie/authorization/password/secret 等敏感信息明文
 * - URL 默认去掉 query/fragment（可定位 host/path；必要时由调用方显式选择更详细模式）
 * - 对磁力链仅保留 btih hash；对本地路径仅保留文件名 + 指纹
 */
object SensitiveDataSanitizer {
    private const val REDACTED = "<redacted>"
    private const val UNKNOWN = "<unknown>"

    private val sensitiveKeys =
        setOf(
            "token",
            "access_token",
            "refresh_token",
            "v_voucher",
            "gt",
            "challenge",
            "validate",
            "seccode",
            "grisk_id",
            "csrf",
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "password",
            "passwd",
            "secret",
            "appsecret",
            "app_sec",
            "appsec",
            "appkey",
            "x-appid",
            "x-appsecret",
            "x-appkey",
            "x-appsec"
        )

    private val urlRegex = "(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\"']+".toRegex()
    private val magnetRegex = "(?i)\\bmagnet:\\?[^\\s\"']+".toRegex()

    private val bearerRegex = "(?i)(authorization\\s*:\\s*bearer\\s+)([^\\s,;]+)".toRegex()

    private val unixPathInTextRegex =
        "(^|\\s|[=,:\\(\\[\\{\\\"'])(/(?:storage|sdcard|data|mnt)/[^\\s\"']+)"
            .toRegex()
    private val windowsPathInTextRegex = "(?i)(^|\\s|[=,:\\(\\[\\{\\\"'])([a-z]:\\\\[^\\s\"']+)".toRegex()

    private val ipv4Regex = "(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})".toRegex()

    private val jsonKeyValueRegex =
        "(?i)(\"(?:token|access_token|refresh_token|v_voucher|gt|challenge|validate|seccode|grisk_id|csrf|password|passwd|secret|appsecret|app_sec|appsec)\"\\s*:\\s*\")([^\"]+)(\")"
            .toRegex()

    private val queryKeyValueRegex =
        "(?i)(\\b(?:token|access_token|refresh_token|v_voucher|gt|challenge|validate|seccode|grisk_id|csrf|password|passwd|secret|appsecret|app_sec|appsec)\\b=)([^&\\s]+)"
            .toRegex()

    private val cookieStyleRegexes =
        listOf(
            "(?i)(SESSDATA=)[^;\\s\"]+".toRegex(),
            "(?i)(bili_jct=)[^;\\s\"]+".toRegex(),
            "(?i)(DedeUserID=)[^;\\s\"]+".toRegex(),
            "(?i)(UID=)[^;\\s\"]+".toRegex(),
            "(?i)(CID=)[^;\\s\"]+".toRegex(),
            "(?i)(SEID=)[^;\\s\"]+".toRegex(),
            "(?i)(KID=)[^;\\s\"]+".toRegex()
        )

    enum class UrlMode {
        /**
         * 默认安全模式：仅保留 scheme/host/port/path，不输出 query/fragment。
         */
        SAFE,

        /**
         * 详细模式：保留 query key，但 value 统一遮蔽；fragment 仍不输出。
         */
        KEYS_ONLY
    }

    fun sanitizeHeader(
        name: String,
        value: String
    ): String {
        if (value.isBlank()) return value
        val key = name.lowercase(Locale.US).trim()
        return when {
            key == "cookie" -> sanitizeCookieHeader(value)
            key == "set-cookie" -> REDACTED
            key == "authorization" -> REDACTED
            key == "proxy-authorization" -> REDACTED
            key == "x-appid" -> REDACTED
            key == "x-appsecret" -> REDACTED
            else -> sanitizeFreeText(value)
        }
    }

    fun sanitizeCookieHeader(value: String): String {
        if (value.isBlank()) return value
        return value
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("; ") { cookie ->
                val equalIndex = cookie.indexOf('=')
                if (equalIndex <= 0) cookie else cookie.substring(0, equalIndex).trim() + "=$REDACTED"
            }
    }

    fun sanitizeUrl(
        raw: String?,
        mode: UrlMode = UrlMode.SAFE
    ): String {
        val url = raw?.trim().orEmpty()
        if (url.isBlank()) return UNKNOWN

        val stripped =
            when {
                url.startsWith("magnet:", ignoreCase = true) -> sanitizeMagnet(url)
                url.startsWith("file:", ignoreCase = true) -> sanitizePath(url)
                else -> url
            }
        if (stripped.startsWith("magnet:", ignoreCase = true)) return stripped

        val uri =
            runCatching { URI(stripped) }.getOrNull()
                ?: return stripQueryFragmentFallback(stripped, mode)

        val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: return stripQueryFragmentFallback(stripped, mode)
        val host = uri.host?.takeIf { it.isNotBlank() }
        val portPart = if (uri.port in 1..65535) ":${uri.port}" else ""
        val authority =
            when {
                host != null -> host + portPart
                uri.rawAuthority != null -> uri.rawAuthority
                else -> null
            }
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: ""

        val queryPart =
            when (mode) {
                UrlMode.SAFE -> ""
                UrlMode.KEYS_ONLY -> sanitizeQueryKeysOnly(uri.rawQuery)
            }

        return buildString {
            append(scheme)
            append("://")
            append(authority ?: UNKNOWN)
            append(path)
            append(queryPart)
        }
    }

    fun sanitizePath(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return UNKNOWN
        val normalized = value.removePrefix("file://")
        val fileName =
            normalized
                .trimEnd('/', '\\')
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .ifBlank { UNKNOWN }
        return "$fileName#${fingerprint(value)}"
    }

    fun sanitizeMagnet(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return UNKNOWN
        val hash = extractMagnetHash(value)
        return if (hash.isNullOrBlank()) {
            "magnet:btih=$REDACTED"
        } else {
            "magnet:btih=$hash"
        }
    }

    fun extractMagnetHash(raw: String): String? {
        val lower = raw.lowercase(Locale.US)
        val marker = "xt=urn:btih:"
        val idx = lower.indexOf(marker)
        if (idx < 0) return null
        val start = idx + marker.length
        val end = lower.indexOf('&', start).takeIf { it > start } ?: lower.length
        val hash = raw.substring(start, end).trim()
        return hash.takeIf { it.isNotBlank() }
    }

    fun sanitizeContext(context: Map<String, String>): Map<String, String> {
        if (context.isEmpty()) return context
        val sanitized = LinkedHashMap<String, String>(context.size)
        context.forEach { (k, v) ->
            sanitized[k] = sanitizeValueByKey(k, v)
        }
        return sanitized
    }

    fun sanitizeParams(params: Map<String, Any?>): Map<String, String> {
        if (params.isEmpty()) return emptyMap()
        val sanitized = LinkedHashMap<String, String>(params.size)
        params.forEach { (k, v) ->
            sanitized[k] = sanitizeValueByKey(k, v?.toString().orEmpty())
        }
        return sanitized
    }

    fun sanitizeFreeText(raw: String): String {
        if (raw.isBlank()) return raw
        var value = raw

        value = value.replace(bearerRegex, "$1$REDACTED")
        value = value.replace(jsonKeyValueRegex, "$1$REDACTED$3")
        value = value.replace(queryKeyValueRegex, "$1$REDACTED")
        cookieStyleRegexes.forEach { regex ->
            value = value.replace(regex, "$1$REDACTED")
        }

        value =
            value.replace(unixPathInTextRegex) { match ->
                val prefix = match.groupValues[1]
                val path = match.groupValues[2]
                prefix + sanitizePath(path)
            }
        value =
            value.replace(windowsPathInTextRegex) { match ->
                val prefix = match.groupValues[1]
                val path = match.groupValues[2]
                prefix + sanitizePath(path)
            }

        value =
            value.replace(urlRegex) { match ->
                sanitizeUrl(match.value, UrlMode.SAFE)
            }
        value =
            value.replace(magnetRegex) { match ->
                sanitizeMagnet(match.value)
            }

        return value
    }

    fun sanitizeValueForKey(
        key: String,
        value: String
    ): String = sanitizeValueByKey(key, value)

    fun fingerprint(raw: String): String {
        if (raw.isBlank()) return "00000000"
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { b -> "%02x".format(b) }.take(8)
    }

    private fun sanitizeValueByKey(
        key: String,
        value: String
    ): String {
        val normalizedKey = key.lowercase(Locale.US).trim()
        if (normalizedKey in sensitiveKeys) return REDACTED

        val trimmedValue = value.trim()
        if (trimmedValue.startsWith("magnet:", ignoreCase = true)) {
            return sanitizeMagnet(trimmedValue)
        }
        if (trimmedValue.contains("://")) {
            return sanitizeUrl(trimmedValue, UrlMode.SAFE)
        }
        if (
            normalizedKey.contains("address") ||
            normalizedKey.contains("host") ||
            normalizedKey == "ip" ||
            normalizedKey == "ipv4" ||
            normalizedKey == "ipv6"
        ) {
            return sanitizeHostPort(trimmedValue)
        }
        if (looksLikeLocalPath(trimmedValue)) {
            return sanitizePath(trimmedValue)
        }
        return sanitizeFreeText(trimmedValue)
    }

    private fun sanitizeHostPort(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return UNKNOWN

        val lastColon = value.lastIndexOf(':')
        val hasPort =
            lastColon in 1 until value.length - 1 &&
                value.indexOf(':') == lastColon &&
                value.substring(lastColon + 1).all { it.isDigit() }
        val host = if (hasPort) value.substring(0, lastColon) else value
        val portPart = if (hasPort) value.substring(lastColon) else ""

        val maskedHost =
            ipv4Regex.matchEntire(host)?.let { match ->
                val a = match.groupValues[1]
                val b = match.groupValues[2]
                val c = match.groupValues[3]
                "$a.$b.$c.*"
            } ?: run {
                "<redacted>#${fingerprint(host)}"
            }

        return maskedHost + portPart
    }

    private fun sanitizeQueryKeysOnly(rawQuery: String?): String {
        val query = rawQuery?.trim().orEmpty()
        if (query.isBlank()) return ""
        val keys =
            query
                .split('&')
                .mapNotNull { it.substringBefore('=').takeIf { key -> key.isNotBlank() } }
        if (keys.isEmpty()) return ""
        return keys.joinToString(prefix = "?", separator = "&") { "$it=$REDACTED" }
    }

    private fun stripQueryFragmentFallback(
        raw: String,
        mode: UrlMode
    ): String {
        val base = raw.substringBefore('#').substringBefore('?')
        if (mode == UrlMode.SAFE) return base
        val query = raw.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        val keysOnly = sanitizeQueryKeysOnly(query)
        return base + keysOnly
    }

    private fun looksLikeLocalPath(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.startsWith("/")) return true
        if (value.contains("\\") && value.length >= 3 && value[1] == ':') return true
        return false
    }
}
