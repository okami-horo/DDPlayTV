package com.xyoye.common_component.storage.file.helper

import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.network.Retrofit
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.RangeUtils
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URLEncoder
import kotlin.random.Random

/**
 * A lightweight local HTTP proxy for players (e.g. libmpv) that struggle with some remote servers.
 * It forwards requests to an upstream URL and provides stable Range responses.
 */
class HttpPlayServer private constructor() : NanoHTTPD(randomPort()) {
    private var upstreamUrl: String? = null
    private var upstreamHeaders: Map<String, String> = emptyMap()
    private var contentType: String = "application/octet-stream"
    private var contentLength: Long = -1L

    @Volatile
    private var seekEnabled: Boolean = false

    private val upstreamRangeLock = Any()

    @Volatile
    private var lastUpstreamRangeAtMs: Long = 0L

    private val maxRangeBytesBeforePlay: Long = 1L * 1024 * 1024
    private val maxRangeBytesAfterPlay: Long = 4L * 1024 * 1024

    companion object {
        private fun randomPort() = Random.nextInt(20000, 30000)

        @JvmStatic
        fun getInstance(): HttpPlayServer = Holder.instance
    }

    private object Holder {
        val instance: HttpPlayServer by lazy { HttpPlayServer() }
    }

    override fun serve(session: IHTTPSession): Response {
        val url =
            upstreamUrl
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "upstream not configured",
                )

        val rangeHeader = session.headers["range"]
        val hasRange = !rangeHeader.isNullOrBlank()
        val supportsRange = contentLength > 0
        val requestedRange =
            if (supportsRange && hasRange) {
                RangeUtils.parseRange(rangeHeader!!, contentLength)
            } else {
                null
            }
        if (supportsRange && hasRange && requestedRange == null) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
        }
        val upstreamRangeHeader =
            if (supportsRange && hasRange && requestedRange != null) {
                val maxBytes = if (seekEnabled) maxRangeBytesAfterPlay else maxRangeBytesBeforePlay
                val start = requestedRange.first
                val end = minOf(requestedRange.second, start + maxBytes - 1)
                "bytes=$start-$end"
            } else {
                null
            }

        val response =
            if (supportsRange && hasRange) {
                synchronized(upstreamRangeLock) {
                    throttleUpstreamRange()
                    fetchUpstream(
                        url,
                        buildUpstreamHeaders(
                            clientHeaders = session.headers,
                            rangeHeader = upstreamRangeHeader,
                            forwardRange = true,
                        ),
                    )
                }
            } else {
                fetchUpstream(
                    url,
                    buildUpstreamHeaders(
                        clientHeaders = session.headers,
                        rangeHeader = null,
                        forwardRange = false,
                    ),
                )
            } ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "upstream request failed",
            )

        if (!response.isSuccessful) {
            // If upstream rejects probing ranges (403), let mpv fall back to linear playback instead of failing open().
            if (supportsRange && hasRange && !seekEnabled && response.code() == 403) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
            }
            return newFixedLengthResponse(
                toStatus(response.code()),
                "text/plain",
                "upstream http ${response.code()}",
            )
        }

        val body =
            response.body()
                ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "empty upstream body",
                )

        val upstreamContentRange = response.headers()["Content-Range"]
        val isPartial = response.code() == 206 || upstreamContentRange != null

        val status = if (isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val contentRange =
            upstreamContentRange ?: requestedRange?.let { (start, end) ->
                "bytes $start-$end/$contentLength"
            }

        val responseLength =
            when {
                supportsRange && isPartial && requestedRange != null ->
                    (requestedRange.second - requestedRange.first + 1).coerceAtLeast(0)
                supportsRange && !isPartial -> contentLength
                else -> body.contentLength()
            }.takeIf { it > 0 } ?: -1L

        val nanoResponse =
            if (responseLength > 0) {
                newFixedLengthResponse(status, contentType, body.byteStream(), responseLength)
            } else {
                newChunkedResponse(status, contentType, body.byteStream())
            }

        if (supportsRange) {
            nanoResponse.addHeader("Accept-Ranges", "bytes")
            contentRange?.let { nanoResponse.addHeader("Content-Range", it) }
        }
        return nanoResponse
    }

    fun setSeekEnabled(enabled: Boolean) {
        seekEnabled = enabled
    }

    fun isServingUrl(url: String): Boolean = url.startsWith("http://127.0.0.1:$listeningPort/")

    private fun toStatus(code: Int): Response.Status =
        when (code) {
            200 -> Response.Status.OK
            206 -> Response.Status.PARTIAL_CONTENT
            400 -> Response.Status.BAD_REQUEST
            401 -> Response.Status.UNAUTHORIZED
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            416 -> Response.Status.RANGE_NOT_SATISFIABLE
            500 -> Response.Status.INTERNAL_ERROR
            503 -> Response.Status.SERVICE_UNAVAILABLE
            else -> Response.Status.INTERNAL_ERROR
        }

    private fun buildUpstreamHeaders(
        clientHeaders: Map<String, String>,
        rangeHeader: String?,
        forwardRange: Boolean
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(upstreamHeaders)
        // Forward a small allowlist of client headers; some upstreams require them (e.g. for Range/auth).
        val existingKeys = merged.keys.map { it.lowercase() }.toSet()

        fun putIfAbsent(
            header: String,
            value: String?
        ) {
            if (value.isNullOrBlank()) return
            if (existingKeys.contains(header.lowercase())) return
            merged[header] = value
        }
        putIfAbsent("User-Agent", clientHeaders["user-agent"])
        putIfAbsent("Referer", clientHeaders["referer"])
        putIfAbsent("Cookie", clientHeaders["cookie"])
        putIfAbsent("Authorization", clientHeaders["authorization"])
        // Ensure byte offsets match the original stream (avoid transparent gzip).
        merged["Accept-Encoding"] = "identity"
        if (forwardRange && !rangeHeader.isNullOrBlank()) {
            merged["Range"] = rangeHeader
        }
        return merged
    }

    private fun fetchUpstream(
        url: String,
        headers: Map<String, String>
    ): retrofit2.Response<okhttp3.ResponseBody>? {
        return runBlocking {
            runCatching { Retrofit.extendedService.getResourceResponse(url, headers) }
                .getOrElse { throwable ->
                    ErrorReportHelper.postCatchedException(
                        throwable,
                        "HttpPlayServer",
                        "上游请求失败 url=$url headers=${headers.keys.joinToString()}",
                    )
                    return@runBlocking null
                }
        }
    }

    private fun throttleUpstreamRange() {
        val now = nowMs()
        val prePlayIntervalMs =
            runCatching { PlayerConfig.getMpvProxyRangeMinIntervalMs() }
                .getOrDefault(200)
                .coerceIn(0, 2000)
                .toLong()
        val minIntervalMs = if (seekEnabled) 20L else prePlayIntervalMs
        val elapsed = now - lastUpstreamRangeAtMs
        val waitMs = minIntervalMs - elapsed
        if (waitMs > 0) {
            runCatching { Thread.sleep(waitMs) }
        }
        lastUpstreamRangeAtMs = nowMs()
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    suspend fun startSync(timeoutMs: Long = 5000): Boolean {
        if (wasStarted()) {
            return true
        }
        return try {
            withTimeout(timeoutMs) {
                start()
                while (isActive) {
                    if (wasStarted()) {
                        return@withTimeout true
                    }
                }
                stop()
                return@withTimeout false
            }
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedException(
                e,
                "HttpPlayServer",
                "启动播放服务器失败: timeout=${timeoutMs}ms",
            )
            false
        }
    }

    fun generatePlayUrl(
        upstreamUrl: String,
        upstreamHeaders: Map<String, String> = emptyMap(),
        contentType: String = "application/octet-stream",
        contentLength: Long = -1L,
        fileName: String = "video"
    ): String {
        this.upstreamUrl = upstreamUrl
        this.upstreamHeaders = upstreamHeaders
        this.contentType = contentType
        this.contentLength = contentLength
        this.seekEnabled = false
        this.lastUpstreamRangeAtMs = 0L
        val encodedFileName = URLEncoder.encode(fileName, "utf-8")
        return "http://127.0.0.1:$listeningPort/$encodedFileName"
    }
}
