package com.xyoye.common_component.storage.file.helper

import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.network.Retrofit
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.RangeUtils
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.net.URLEncoder
import kotlin.random.Random

/**
 * A lightweight local HTTP proxy for players (e.g. libmpv) that struggle with some remote servers.
 * It forwards requests to an upstream URL and provides stable Range responses.
 */
class HttpPlayServer private constructor() : NanoHTTPD(randomPort()) {
    private val logTag = "HttpPlayServer"

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
    private val rangeLogIntervalMs: Long = 1000L

    @Volatile
    private var lastRangeLogAtMs: Long = 0L

    @Volatile
    private var loggedNoRangeRequest: Boolean = false

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
        val isRangeRequest = supportsRange && hasRange && requestedRange != null
        val cappedRange =
            requestedRange?.let { range ->
                val maxBytes = if (seekEnabled) maxRangeBytesAfterPlay else maxRangeBytesBeforePlay
                val start = range.first
                val end = minOf(range.second, start + maxBytes - 1)
                start to end
            }
        val upstreamRangeHeader = cappedRange?.let { (start, end) -> "bytes=$start-$end" }
        val urlHash = url.hashCode().toString()

        if (supportsRange && !hasRange && !loggedNoRangeRequest) {
            loggedNoRangeRequest = true
            LogFacade.w(
                LogModule.STORAGE,
                logTag,
                "proxy request without range header",
                context =
                    mapOf(
                        "urlHash" to urlHash,
                        "contentLength" to contentLength.toString(),
                        "seekEnabled" to seekEnabled.toString(),
                        "clientUa" to (session.headers["user-agent"] ?: "null"),
                    ),
            )
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

        val upstreamContentRangeHeader = response.headers()["Content-Range"]
        val upstreamContentRange = upstreamContentRangeHeader?.let { parseContentRange(it) }
        val isPartial = isRangeRequest || response.code() == 206 || upstreamContentRangeHeader != null

        val responseRange =
            when {
                cappedRange != null && upstreamContentRange != null -> {
                    val start = maxOf(cappedRange.first, upstreamContentRange.first)
                    val end = minOf(cappedRange.second, upstreamContentRange.second)
                    if (start <= end) start to end else upstreamContentRange
                }
                upstreamContentRange != null -> upstreamContentRange
                else -> cappedRange
            }

        val status = if (isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val contentRange =
            if (isPartial && responseRange != null) {
                "bytes ${responseRange.first}-${responseRange.second}/$contentLength"
            } else {
                null
            }

        val responseLength =
            when {
                supportsRange && isPartial && responseRange != null ->
                    (responseRange.second - responseRange.first + 1).coerceAtLeast(0)
                supportsRange && !isPartial -> contentLength
                else -> body.contentLength()
            }.takeIf { it > 0 } ?: -1L

        val bodyStream =
            if (isPartial && responseLength > 0) {
                limitStream(body.byteStream(), responseLength)
            } else {
                body.byteStream()
            }
        val nanoResponse =
            if (responseLength > 0) {
                newFixedLengthResponse(status, contentType, bodyStream, responseLength)
            } else {
                newChunkedResponse(status, contentType, bodyStream)
            }

        if (supportsRange) {
            nanoResponse.addHeader("Accept-Ranges", "bytes")
            if (isPartial) {
                contentRange?.let { nanoResponse.addHeader("Content-Range", it) }
            }
        }

        if (isRangeRequest) {
            val upstreamBodyLength = body.contentLength()
            val suspiciousUpstream = response.code() != 206 && upstreamContentRangeHeader == null
            val nowMs = nowMs()
            if (suspiciousUpstream || shouldLogRange(nowMs)) {
                val logFn = if (suspiciousUpstream) LogFacade::w else LogFacade::d
                val context =
                    mapOf(
                        "urlHash" to urlHash,
                        "rangeHeader" to (rangeHeader ?: "null"),
                        "requestedRange" to formatRange(requestedRange),
                        "cappedRange" to formatRange(cappedRange),
                        "upstreamRange" to (upstreamRangeHeader ?: "null"),
                        "upstreamCode" to response.code().toString(),
                        "upstreamContentRange" to (upstreamContentRangeHeader ?: "null"),
                        "upstreamBodyLength" to upstreamBodyLength.toString(),
                        "responseRange" to formatRange(responseRange),
                        "responseLength" to responseLength.toString(),
                        "status" to status.toString(),
                        "contentLength" to contentLength.toString(),
                        "seekEnabled" to seekEnabled.toString(),
                    )
                logFn.invoke(
                    LogModule.STORAGE,
                    logTag,
                    "proxy range response",
                    context,
                    null,
                )
            }
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

    private fun parseContentRange(contentRange: String): Pair<Long, Long>? {
        val trimmed = contentRange.trim()
        if (!trimmed.startsWith("bytes", ignoreCase = true)) return null
        val afterUnit = trimmed.substring(5).trimStart()
        val rangePart = afterUnit.removePrefix("=").trimStart()
        val slashIndex = rangePart.indexOf('/')
        val rangeValue = if (slashIndex >= 0) rangePart.substring(0, slashIndex).trim() else rangePart
        if (rangeValue == "*" || rangeValue.isEmpty()) return null
        val dashIndex = rangeValue.indexOf('-')
        if (dashIndex <= 0 || dashIndex == rangeValue.length - 1) return null
        val start = rangeValue.substring(0, dashIndex).toLongOrNull() ?: return null
        val end = rangeValue.substring(dashIndex + 1).toLongOrNull() ?: return null
        if (start < 0 || end < start) return null
        return start to end
    }

    private fun formatRange(range: Pair<Long, Long>?): String {
        return range?.let { "${it.first}-${it.second}" } ?: "null"
    }

    private fun shouldLogRange(nowMs: Long): Boolean {
        val elapsed = nowMs - lastRangeLogAtMs
        if (elapsed < rangeLogIntervalMs) return false
        lastRangeLogAtMs = nowMs
        return true
    }

    private fun limitStream(
        stream: InputStream,
        maxBytes: Long
    ): InputStream {
        if (maxBytes <= 0) return stream
        return object : InputStream() {
            private var remaining = maxBytes
            private var closed = false

            override fun read(): Int {
                if (remaining <= 0) return -1
                val value = stream.read()
                if (value == -1) return -1
                remaining -= 1
                return value
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int
            ): Int {
                if (remaining <= 0) return -1
                val allowed = minOf(remaining.toInt(), length)
                val count = stream.read(buffer, offset, allowed)
                if (count == -1) return -1
                remaining -= count.toLong()
                return count
            }

            override fun available(): Int {
                val available = stream.available()
                return if (remaining < available) remaining.toInt() else available
            }

            override fun close() {
                if (closed) return
                closed = true
                stream.close()
            }
        }
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
                .getOrDefault(1000)
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
