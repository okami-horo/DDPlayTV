package com.xyoye.common_component.storage.file.helper

import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.network.service.ExtendedService
import com.xyoye.common_component.storage.file.helper.HttpPlayServer.UpstreamSource
import com.xyoye.common_component.storage.file.helper.HttpPlayServer.UpstreamTlsPolicy
import com.xyoye.common_component.storage.transport.releaseTransportResources
import com.xyoye.common_component.storage.transport.requireUnownedPlaybackUrl
import com.xyoye.common_component.utils.RangeUtils
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import okhttp3.ResponseBody
import retrofit2.Call
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Collections
import java.util.IdentityHashMap

/** Request state and in-flight work belong to one lease, independently of the server. */
internal class HttpUpstreamContext(
    private val leaseId: String,
    initialSource: UpstreamSource,
    rangeMinIntervalMs: Long,
    onRangeUnsupported: (() -> UpstreamSource?)?,
    private val serviceFor: (UpstreamTlsPolicy) -> ExtendedService
) {
    private val logTag = "HttpPlayServer"
    private val resourcesLock = Any()
    private val requestLock = Any()
    private var retired = false
    private val calls = mutableSetOf<Call<ResponseBody>>()
    private val bodies = Collections.newSetFromMap(IdentityHashMap<ResponseBody, Boolean>())

    private val rangeRetryLock = Any()

    private var upstreamUrl: String? = initialSource.url
    private var upstreamHeaders: Map<String, String> = initialSource.headers.toMap()
    private var contentType: String = initialSource.contentType
    private var contentLength: Long = initialSource.contentLength
    private var upstreamTlsPolicy: UpstreamTlsPolicy = initialSource.tlsPolicy

    private val prePlayRangeMinIntervalMs: Long = rangeMinIntervalMs

    @Volatile
    private var rangeRetryDone: Boolean = false
    private val rangeRetrySupplier: (() -> UpstreamSource?)? = onRangeUnsupported

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

    fun serve(session: IHTTPSession): Response =
        synchronized(requestLock) {
            if (isRetired()) notFound() else serveInternal(session, allowRetry = true)
        }

    fun retire() {
        val cleanup =
            synchronized(resourcesLock) {
                if (retired) return
                retired = true
                val actions = calls.map { call -> { call.cancel() } } + bodies.map { body -> { body.close() } }
                calls.clear()
                bodies.clear()
                actions
            }
        releaseTransportResources(leaseId, cleanup)
    }

    private fun isRetired(): Boolean = synchronized(resourcesLock) { retired }

    private fun closeBody(body: ResponseBody) {
        val shouldClose = synchronized(resourcesLock) { bodies.remove(body) }
        if (shouldClose) releaseTransportResources(leaseId, listOf({ body.close() }))
    }

    private fun closeResponse(response: retrofit2.Response<ResponseBody>) {
        response.body()?.let(::closeBody)
        response.errorBody()?.let(::closeBody)
    }

    private fun bodyStream(body: ResponseBody): InputStream =
        object : FilterInputStream(body.byteStream()) {
            override fun close() = closeBody(body)
        }

    private fun notFound(): Response = newFixedLengthResponse(Response.Status.NOT_FOUND, TEXT_PLAIN_CONTENT_TYPE, "")

    private data class ServeRequestContext(
        val url: String,
        val rangeHeader: String?,
        val hasRange: Boolean,
        val supportsRange: Boolean,
        val requestedRange: Pair<Long, Long>?,
        val invalidRange: Boolean,
        val isRangeRequest: Boolean,
        val cappedRange: Pair<Long, Long>?,
        val upstreamRangeHeader: String?,
        val urlHash: String
    )

    private data class NanoResponseBuildResult(
        val nanoResponse: Response,
        val status: Response.Status,
        val responseLength: Long,
        val contentRange: String?
    )

    private fun serveInternal(
        session: IHTTPSession,
        allowRetry: Boolean
    ): Response {
        val url =
            upstreamUrl
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    TEXT_PLAIN_CONTENT_TYPE,
                    "upstream not configured"
                )

        val requestContext = buildServeRequestContext(session, url)
        if (requestContext.invalidRange) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, TEXT_PLAIN_CONTENT_TYPE, "")
        }

        logNoRangeRequestIfNeeded(requestContext, session.headers)

        val response =
            fetchUpstreamResponse(requestContext, session.headers)
                ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    TEXT_PLAIN_CONTENT_TYPE,
                    "upstream request failed"
                )

        if (!response.isSuccessful) {
            closeResponse(response)
            return buildUpstreamFailureResponse(requestContext, response)
        }

        val body =
            response.body()
                ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    TEXT_PLAIN_CONTENT_TYPE,
                    "empty upstream body"
                )

        val upstreamContentRangeHeader = response.headers()["Content-Range"]
        val unsupportedRangeResponse =
            handleUnsupportedRange(
                session = session,
                allowRetry = allowRetry,
                requestContext = requestContext,
                response = response,
                body = body,
                upstreamContentRangeHeader = upstreamContentRangeHeader
            )
        if (unsupportedRangeResponse != null) {
            closeBody(body)
            return unsupportedRangeResponse
        }

        val upstreamContentRange = upstreamContentRangeHeader?.let { parseContentRange(it) }
        val isPartial =
            requestContext.isRangeRequest ||
                response.code() == 206 ||
                upstreamContentRangeHeader != null
        val responseRange = resolveResponseRange(requestContext.cappedRange, upstreamContentRange)
        val nanoResult = buildNanoResponse(body, requestContext.supportsRange, isPartial, responseRange)

        applyRangeHeaders(
            nanoResponse = nanoResult.nanoResponse,
            supportsRange = requestContext.supportsRange,
            isPartial = isPartial,
            contentRange = nanoResult.contentRange
        )

        logRangeResponse(
            requestContext = requestContext,
            response = response,
            body = body,
            upstreamContentRangeHeader = upstreamContentRangeHeader,
            responseRange = responseRange,
            status = nanoResult.status,
            responseLength = nanoResult.responseLength
        )

        if (isRetired()) {
            nanoResult.nanoResponse.close()
            return notFound()
        }
        return nanoResult.nanoResponse
    }

    private fun buildServeRequestContext(
        session: IHTTPSession,
        url: String
    ): ServeRequestContext {
        val rangeHeader = session.headers["range"]
        val hasRange = !rangeHeader.isNullOrBlank()
        val supportsRange = contentLength > 0
        val requestedRange =
            if (supportsRange && hasRange) {
                RangeUtils.parseRange(rangeHeader!!, contentLength)
            } else {
                null
            }
        val invalidRange = supportsRange && hasRange && requestedRange == null
        val isRangeRequest = supportsRange && hasRange && requestedRange != null
        val cappedRange =
            requestedRange?.let { range ->
                val maxBytes = if (seekEnabled) maxRangeBytesAfterPlay else maxRangeBytesBeforePlay
                val start = range.first
                val end = minOf(range.second, start + maxBytes - 1)
                start to end
            }

        return ServeRequestContext(
            url = url,
            rangeHeader = rangeHeader,
            hasRange = hasRange,
            supportsRange = supportsRange,
            requestedRange = requestedRange,
            invalidRange = invalidRange,
            isRangeRequest = isRangeRequest,
            cappedRange = cappedRange,
            upstreamRangeHeader = cappedRange?.let { (start, end) -> "bytes=$start-$end" },
            urlHash = url.hashCode().toString()
        )
    }

    private fun logNoRangeRequestIfNeeded(
        requestContext: ServeRequestContext,
        headers: Map<String, String>
    ) {
        if (!requestContext.supportsRange || requestContext.hasRange || loggedNoRangeRequest) {
            return
        }
        loggedNoRangeRequest = true
        LogFacade.w(
            LogModule.STORAGE,
            logTag,
            "proxy request without range header",
            context =
                mapOf(
                    "leaseId" to leaseId,
                    "urlHash" to requestContext.urlHash,
                    "contentLength" to contentLength.toString(),
                    "seekEnabled" to seekEnabled.toString(),
                    "clientUa" to (headers["user-agent"] ?: "null")
                )
        )
    }

    private fun fetchUpstreamResponse(
        requestContext: ServeRequestContext,
        headers: Map<String, String>
    ): retrofit2.Response<okhttp3.ResponseBody>? {
        val shouldForwardRange = requestContext.supportsRange && requestContext.hasRange
        if (shouldForwardRange) {
            return synchronized(upstreamRangeLock) {
                throttleUpstreamRange()
                fetchUpstream(
                    requestContext.url,
                    buildUpstreamHeaders(
                        clientHeaders = headers,
                        rangeHeader = requestContext.upstreamRangeHeader,
                        forwardRange = true
                    )
                )
            }
        }

        return fetchUpstream(
            requestContext.url,
            buildUpstreamHeaders(
                clientHeaders = headers,
                rangeHeader = null,
                forwardRange = false
            )
        )
    }

    private fun buildUpstreamFailureResponse(
        requestContext: ServeRequestContext,
        response: retrofit2.Response<okhttp3.ResponseBody>
    ): Response {
        // If upstream rejects probing ranges (403), let mpv fall back to linear playback instead of failing open().
        if (requestContext.supportsRange && requestContext.hasRange && !seekEnabled && response.code() == 403) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, TEXT_PLAIN_CONTENT_TYPE, "")
        }
        return newFixedLengthResponse(
            toStatus(response.code()),
            TEXT_PLAIN_CONTENT_TYPE,
            "upstream http ${response.code()}"
        )
    }

    private fun handleUnsupportedRange(
        session: IHTTPSession,
        allowRetry: Boolean,
        requestContext: ServeRequestContext,
        response: retrofit2.Response<okhttp3.ResponseBody>,
        body: okhttp3.ResponseBody,
        upstreamContentRangeHeader: String?
    ): Response? {
        if (!requestContext.isRangeRequest || (response.code() == 206 && upstreamContentRangeHeader != null)) {
            return null
        }

        if (allowRetry && !rangeRetryDone) {
            synchronized(rangeRetryLock) {
                if (!rangeRetryDone) {
                    val refreshed =
                        runCatching {
                            rangeRetrySupplier?.invoke()?.also { requireUnownedPlaybackUrl(it.url) }
                        }.getOrNull()
                    if (refreshed != null) {
                        rangeRetryDone = true
                        upstreamUrl = refreshed.url
                        upstreamHeaders = refreshed.headers.toMap()
                        contentType = refreshed.contentType
                        contentLength = refreshed.contentLength
                        upstreamTlsPolicy = refreshed.tlsPolicy
                        closeBody(body)
                        return serveInternal(session, allowRetry = false)
                    }
                }
            }
        }

        val upstreamBodyLength = body.contentLength()
        LogFacade.w(
            LogModule.STORAGE,
            logTag,
            "upstream range unsupported",
            context =
                mapOf(
                    "leaseId" to leaseId,
                    "urlHash" to requestContext.urlHash,
                    "rangeHeader" to (requestContext.rangeHeader ?: "null"),
                    "requestedRange" to formatRange(requestContext.requestedRange),
                    "upstreamRange" to (requestContext.upstreamRangeHeader ?: "null"),
                    "upstreamCode" to response.code().toString(),
                    "upstreamContentRange" to (upstreamContentRangeHeader ?: "null"),
                    "upstreamBodyLength" to upstreamBodyLength.toString(),
                    "contentLength" to contentLength.toString(),
                    "seekEnabled" to seekEnabled.toString()
                )
        )
        return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            TEXT_PLAIN_CONTENT_TYPE,
            "upstream range unsupported"
        )
    }

    private fun resolveResponseRange(
        cappedRange: Pair<Long, Long>?,
        upstreamContentRange: Pair<Long, Long>?
    ): Pair<Long, Long>? =
        when {
            cappedRange != null && upstreamContentRange != null -> {
                val start = maxOf(cappedRange.first, upstreamContentRange.first)
                val end = minOf(cappedRange.second, upstreamContentRange.second)
                if (start <= end) start to end else upstreamContentRange
            }
            upstreamContentRange != null -> upstreamContentRange
            else -> cappedRange
        }

    private fun buildNanoResponse(
        body: okhttp3.ResponseBody,
        supportsRange: Boolean,
        isPartial: Boolean,
        responseRange: Pair<Long, Long>?
    ): NanoResponseBuildResult {
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

        val ownedStream = bodyStream(body)
        val bodyStream =
            if (isPartial && responseLength > 0) {
                limitStream(ownedStream, responseLength)
            } else {
                ownedStream
            }

        val nanoResponse =
            if (responseLength > 0) {
                newFixedLengthResponse(status, contentType, bodyStream, responseLength)
            } else {
                newChunkedResponse(status, contentType, bodyStream)
            }

        return NanoResponseBuildResult(
            nanoResponse = nanoResponse,
            status = status,
            responseLength = responseLength,
            contentRange = contentRange
        )
    }

    private fun applyRangeHeaders(
        nanoResponse: Response,
        supportsRange: Boolean,
        isPartial: Boolean,
        contentRange: String?
    ) {
        if (!supportsRange) {
            return
        }
        nanoResponse.addHeader("Accept-Ranges", "bytes")
        if (isPartial) {
            contentRange?.let { nanoResponse.addHeader("Content-Range", it) }
        }
    }

    private fun logRangeResponse(
        requestContext: ServeRequestContext,
        response: retrofit2.Response<okhttp3.ResponseBody>,
        body: okhttp3.ResponseBody,
        upstreamContentRangeHeader: String?,
        responseRange: Pair<Long, Long>?,
        status: Response.Status,
        responseLength: Long
    ) {
        if (!requestContext.isRangeRequest) {
            return
        }

        val upstreamBodyLength = body.contentLength()
        val suspiciousUpstream = response.code() != 206 && upstreamContentRangeHeader == null
        if (suspiciousUpstream || shouldLogRange(nowMs())) {
            val logFn = if (suspiciousUpstream) LogFacade::w else LogFacade::d
            val context =
                mutableMapOf(
                    "leaseId" to leaseId,
                    "urlHash" to requestContext.urlHash,
                    "rangeHeader" to (requestContext.rangeHeader ?: "null"),
                    "requestedRange" to formatRange(requestContext.requestedRange),
                    "cappedRange" to formatRange(requestContext.cappedRange),
                    "upstreamRange" to (requestContext.upstreamRangeHeader ?: "null"),
                    "upstreamCode" to response.code().toString(),
                    "upstreamContentRange" to (upstreamContentRangeHeader ?: "null"),
                    "upstreamBodyLength" to upstreamBodyLength.toString(),
                    "responseRange" to formatRange(responseRange),
                    "responseLength" to responseLength.toString(),
                    "status" to status.toString(),
                    "contentLength" to contentLength.toString(),
                    "seekEnabled" to seekEnabled.toString()
                )
            logFn.invoke(
                LogModule.STORAGE,
                logTag,
                "proxy range response",
                context,
                null
            )
        }
    }

    fun setSeekEnabled(enabled: Boolean) {
        seekEnabled = enabled
    }

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

    private fun formatRange(range: Pair<Long, Long>?): String = range?.let { "${it.first}-${it.second}" } ?: "null"

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
    ): retrofit2.Response<ResponseBody>? {
        val call = serviceFor(upstreamTlsPolicy).getResourceResponseCall(url, headers)
        synchronized(resourcesLock) {
            if (retired) return null
            calls.add(call)
        }
        try {
            val response = call.execute()
            val responseBodies = listOfNotNull(response.body(), response.errorBody())
            synchronized(resourcesLock) {
                if (!retired) {
                    bodies.addAll(responseBodies)
                    return response
                }
            }
            releaseTransportResources(leaseId, responseBodies.map { body -> { body.close() } })
            return null
        } catch (_: Exception) {
            LogFacade.w(
                LogModule.STORAGE,
                logTag,
                "Upstream request failed",
                mapOf("leaseId" to leaseId)
            )
            return null
        } finally {
            synchronized(resourcesLock) { calls.remove(call) }
        }
    }

    private fun throttleUpstreamRange() {
        val now = nowMs()
        val minIntervalMs =
            if (seekEnabled) {
                20L
            } else {
                prePlayRangeMinIntervalMs.coerceIn(0, 2000)
            }
        val elapsed = now - lastUpstreamRangeAtMs
        val waitMs = minIntervalMs - elapsed
        if (waitMs > 0) {
            runCatching { Thread.sleep(waitMs) }
        }
        lastUpstreamRangeAtMs = nowMs()
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val TEXT_PLAIN_CONTENT_TYPE = "text/plain"
    }
}
