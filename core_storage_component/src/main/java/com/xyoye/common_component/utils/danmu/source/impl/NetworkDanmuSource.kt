package com.xyoye.common_component.utils.danmu.source.impl

import android.net.Uri
import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.common_component.network.request.NetworkException
import com.xyoye.common_component.utils.danmu.source.AbstractDanmuSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder

/**
 * Created by xyoye on 2024/1/14.
 */

class NetworkDanmuSource(
    private val url: String,
    private val headers: Map<String, String> = emptyMap()
) : AbstractDanmuSource() {
    override suspend fun hash(): String? =
        withTimeoutOrNull(NETWORK_HASH_TIMEOUT_MS) {
            super.hash()
        }

    override suspend fun fileName(): String? {
        val segment = Uri.parse(url)?.lastPathSegment ?: URI(url).path?.substringAfterLast("/") ?: return null
        return URLDecoder
            .decode(segment, "UTF-8")
            .substringBeforeLast(".")
            .ifBlank { null }
    }

    override suspend fun fileSize(): Long? = null

    override suspend fun getStream(): InputStream? =
        retryWithBackoff {
            ResourceRepository
                .getResourceResponseBody(url, headers)
                .getOrThrow()
                .byteStream()
        }

    /**
     * 带指数退避的重试机制
     */
    private suspend fun <T> retryWithBackoff(
        maxRetries: Int = 3,
        initialDelay: Long = 1000,
        maxDelay: Long = 5000,
        operation: suspend () -> T?
    ): T? {
        var currentDelay = initialDelay

        repeat(maxRetries) { retryCount ->
            try {
                return operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cause = if (e is NetworkException) e.cause else e
                if (cause is CancellationException) throw cause
                if (retryCount == maxRetries - 1 || (cause !is SocketException && cause !is InterruptedIOException)) return null
            }
            delay(currentDelay)
            currentDelay = minOf(currentDelay * 2, maxDelay)
        }

        return null
    }

    companion object {
        private const val NETWORK_HASH_TIMEOUT_MS = 30_000L
    }
}
