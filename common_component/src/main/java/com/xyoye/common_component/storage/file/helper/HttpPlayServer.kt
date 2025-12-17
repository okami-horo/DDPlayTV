package com.xyoye.common_component.storage.file.helper

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

    companion object {
        private fun randomPort() = Random.nextInt(20000, 30000)

        @JvmStatic
        fun getInstance(): HttpPlayServer = Holder.instance
    }

    private object Holder {
        val instance: HttpPlayServer by lazy { HttpPlayServer() }
    }

    override fun serve(session: IHTTPSession): Response {
        val url = upstreamUrl
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "upstream not configured"
            )

        val rangeHeader = session.headers["range"]
        val supportsRange = contentLength > 0
        if (!supportsRange && !rangeHeader.isNullOrBlank()) {
            // Allow trivial "from start" ranges, but reject large random access seeks.
            val requested = runCatching { RangeUtils.parseRange(rangeHeader, Long.MAX_VALUE) }.getOrNull()
            val isFromStart = requested?.first == 0L || rangeHeader.trim().startsWith("bytes=0-", ignoreCase = true)
            if (!isFromStart) {
                return newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE,
                    "text/plain",
                    ""
                )
            }
        }
        val headers = buildUpstreamHeaders(rangeHeader, supportsRange)

        val totalLength = contentLength
        val requestedRange = if (supportsRange && !rangeHeader.isNullOrBlank()) {
            RangeUtils.parseRange(rangeHeader, totalLength)
        } else {
            null
        }

        val response = runBlocking {
            runCatching { Retrofit.extendedService.getResourceResponse(url, headers) }
                .getOrElse { throwable ->
                    ErrorReportHelper.postCatchedException(
                        throwable,
                        "HttpPlayServer",
                        "上游请求失败 url=$url range=${rangeHeader.orEmpty()}"
                    )
                    return@runBlocking null
                }
        } ?: return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            "text/plain",
            "upstream request failed"
        )

        if (!response.isSuccessful) {
            return newFixedLengthResponse(
                toStatus(response.code()),
                "text/plain",
                "upstream http ${response.code()}"
            )
        }

        val body = response.body()
            ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "empty upstream body"
            )

        val upstreamContentRange = response.headers()["Content-Range"]
        val isPartial = response.code() == 206 || upstreamContentRange != null

        val status = if (isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val contentRange = upstreamContentRange ?: requestedRange?.let { (start, end) ->
            if (totalLength > 0) "bytes $start-$end/$totalLength" else null
        }

        val responseLength = when {
            supportsRange && isPartial && requestedRange != null ->
                (requestedRange.second - requestedRange.first + 1).coerceAtLeast(0)
            supportsRange && !isPartial && totalLength > 0 -> totalLength
            // When length is unknown, keep response chunked to avoid libmpv attempting large seeks.
            else -> -1L
        }

        val nanoResponse = if (responseLength > 0) {
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

    private fun toStatus(code: Int): Response.Status {
        return when (code) {
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
    }

    private fun buildUpstreamHeaders(rangeHeader: String?, supportsRange: Boolean): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(upstreamHeaders)
        // Ensure byte offsets match the original stream (avoid transparent gzip).
        merged["Accept-Encoding"] = "identity"
        if (supportsRange && !rangeHeader.isNullOrBlank()) {
            merged["Range"] = rangeHeader
        }
        return merged
    }

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
                "启动播放服务器失败: timeout=${timeoutMs}ms"
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
        val encodedFileName = URLEncoder.encode(fileName, "utf-8")
        return "http://127.0.0.1:$listeningPort/$encodedFileName"
    }
}
