package com.xyoye.common_component.utils.danmu.source.impl

import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.common_component.utils.danmu.source.AbstractDanmuSource
import kotlinx.coroutines.delay
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.SocketException
import java.io.InterruptedIOException

/**
 * Created by xyoye on 2024/1/14.
 */

class NetworkDanmuSource(
    private val url: String,
    private val headers: Map<String, String> = emptyMap()
) : AbstractDanmuSource() {

    override suspend fun getStream(): InputStream? {
        return retryWithBackoff {
            ResourceRepository.getResourceResponseBody(url, headers)
                .getOrNull()
                ?.byteStream()
        }
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
            } catch (e: SocketException) {
                if (retryCount == maxRetries - 1) {
                    return null
                }
                delay(currentDelay)
                currentDelay = minOf(currentDelay * 2, maxDelay)
            } catch (e: SocketTimeoutException) {
                if (retryCount == maxRetries - 1) {
                    return null
                }
                delay(currentDelay)
                currentDelay = minOf(currentDelay * 2, maxDelay)
            } catch (e: InterruptedIOException) {
                if (retryCount == maxRetries - 1) {
                    return null
                }
                delay(currentDelay)
                currentDelay = minOf(currentDelay * 2, maxDelay)
            } catch (e: Exception) {
                // 其他异常直接返回null，不进行重试
                return null
            }
        }

        return null
    }
}