package com.xyoye.common_component.bilibili.error

import android.net.Uri
import com.xyoye.common_component.bilibili.BilibiliKeys
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.utils.AuthenticationHelper
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.enums.MediaType

/**
 * Bilibili 播放相关错误上报：
 * - 避免直接上报包含 Cookie/Token 的原始 URL / Header
 * - 统一以结构化 key=value 形式附带上下文，便于 Bugly 检索与归因
 */
object BilibiliPlaybackErrorReporter {
    private const val TAG = "BILI-PLAYBACK"
    private const val CLASS_NAME = "BilibiliPlayback"

    data class SourceSnapshot(
        val mediaType: MediaType,
        val storageId: Int,
        val storagePath: String?,
        val uniqueKey: String,
        val videoTitle: String,
        val videoUrl: String,
        val httpHeader: Map<String, String>?,
    )

    fun isBilibiliSource(mediaType: MediaType?): Boolean =
        mediaType == MediaType.BILIBILI_STORAGE

    fun isBilibiliSource(source: SourceSnapshot?): Boolean =
        isBilibiliSource(source?.mediaType)

    fun isBilibiliLive(
        mediaType: MediaType?,
        uniqueKey: String?,
    ): Boolean =
        isBilibiliSource(mediaType) &&
            uniqueKey != null &&
            (BilibiliKeys.parse(uniqueKey) is BilibiliKeys.LiveKey)

    fun isBilibiliLive(source: SourceSnapshot?): Boolean =
        source != null && isBilibiliLive(source.mediaType, source.uniqueKey)

    fun reportPlaybackError(
        source: SourceSnapshot,
        throwable: Throwable?,
        scene: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        val info = buildReportInfo(source, scene, extra)
        LogFacade.e(LogModule.PLAYER, TAG, "report error scene=$scene $info")

        val enrichedExtra =
            extra.toMutableMap().apply {
                if (throwable != null) {
                    put("throwableType", throwable::class.java.name)
                    put("throwableMessage", throwable.message.orEmpty())
                }
            }

        val maybeThrowable = throwable ?: RuntimeException("Bilibili playback error without throwable")
        ErrorReportHelper.postCatchedExceptionWithContext(
            maybeThrowable,
            CLASS_NAME,
            scene,
            buildReportInfo(source, scene, enrichedExtra),
        )
    }

    fun reportUnexpectedCompletion(
        source: SourceSnapshot,
        scene: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        val info = buildReportInfo(source, scene, extra)
        LogFacade.w(LogModule.PLAYER, TAG, "report completion scene=$scene $info")

        val throwable = IllegalStateException("Bilibili playback completed unexpectedly, scene=$scene")
        ErrorReportHelper.postCatchedExceptionWithContext(
            throwable,
            CLASS_NAME,
            scene,
            info,
        )
    }

    private fun buildReportInfo(
        source: SourceSnapshot,
        scene: String,
        extra: Map<String, String>,
    ): String {
        val headers = source.httpHeader.orEmpty()
        val headerKeys = headers.keys.sorted().joinToString(separator = ",")
        val hasCookieHeader = headers.keys.any { it.equals("cookie", ignoreCase = true) }

        val biliKey = BilibiliKeys.parse(source.uniqueKey)
        val biliKeyInfo =
            when (biliKey) {
                is BilibiliKeys.LiveKey -> "type=live roomId=${biliKey.roomId}"
                is BilibiliKeys.ArchiveKey -> "type=archive bvid=${biliKey.bvid} cid=${biliKey.cid}"
                is BilibiliKeys.PgcEpisodeKey -> "type=pgc_episode epId=${biliKey.epId} cid=${biliKey.cid} sid=${biliKey.seasonId} aid=${biliKey.avid}"
                is BilibiliKeys.PgcSeasonKey -> "type=pgc_season sid=${biliKey.seasonId}"
                null -> "type=unknown"
            }

        val sanitizedUrl = sanitizeUrl(source.videoUrl)
        val authenticationDiagnosis =
            extra["httpResponseCode"]?.toIntOrNull()
                ?.takeIf { it == 403 }
                ?.let { AuthenticationHelper.getAuthenticationDiagnosis() }

        return buildString {
            append("event=").append(scene)
            append(" mediaType=").append(source.mediaType.name)
            append(" storageId=").append(source.storageId)
            append(" storagePath=").append(source.storagePath.orEmpty())
            append(" uniqueKey=").append(source.uniqueKey)
            append(" biliKey={").append(biliKeyInfo).append("}")
            append(" title=").append(source.videoTitle)
            append(" url=").append(sanitizedUrl.orEmpty())
            append(" headerKeys=[").append(headerKeys).append("]")
            append(" hasCookieHeader=").append(hasCookieHeader)

            if (extra.isNotEmpty()) {
                val items =
                    extra.entries
                        .sortedBy { it.key }
                        .joinToString(separator = ",") { (k, v) -> "$k=${sanitizeValue(k, v)}" }
                append(" extra={").append(items).append("}")
            }

            if (!authenticationDiagnosis.isNullOrBlank()) {
                append(" authDiagnosis=").append(authenticationDiagnosis.replace("\n", "\\n"))
            }
        }
    }

    private fun sanitizeValue(
        key: String,
        value: String,
    ): String {
        if (value.isBlank()) return value
        if (key.contains("cookie", ignoreCase = true) || key.contains("authorization", ignoreCase = true)) {
            return "<redacted>"
        }
        if (key.contains("url", ignoreCase = true)) {
            return sanitizeUrl(value).orEmpty()
        }
        return value.take(300)
    }

    private fun sanitizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return "<invalid_url>"
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") {
            return url.take(300)
        }

        val allowValueKeys =
            setOf(
                "expires",
                "expire",
                "deadline",
                "ts",
                "timestamp",
                "qn",
                "quality",
            )

        return buildString {
            append(scheme)
            append("://")
            append(uri.authority.orEmpty())
            append(uri.path.orEmpty())

            val names = runCatching { uri.queryParameterNames }.getOrNull().orEmpty()
            if (names.isNotEmpty()) {
                append("?")
                append(
                    names.sorted().joinToString("&") { name ->
                        val value = uri.getQueryParameter(name).orEmpty()
                        val safeValue =
                            if (allowValueKeys.contains(name.lowercase())) {
                                value.take(120)
                            } else {
                                "<redacted>"
                            }
                        "$name=$safeValue"
                    },
                )
            }
        }
    }
}
