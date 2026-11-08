package com.xyoye.common_component.utils.danmu.source

import com.xyoye.common_component.network.RetrofitManager
import com.xyoye.common_component.network.RetrofitServiceProvider
import com.xyoye.common_component.network.service.ExtendedService
import com.xyoye.common_component.utils.danmu.source.impl.NetworkDanmuSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class NetworkDanmuSourceRetryTest {
    @After fun reset() = RetrofitManager.resetServiceProvider()

    @Test fun timeoutRetriesAndReturnsRecoveredStream() =
        runBlocking {
            var calls = 0
            install {
                if (++calls < 3) throw SocketTimeoutException("temporary")
                "video".toResponseBody()
            }
            val source = NetworkDanmuSource("https://fixture.test/video.mkv")
            assertEquals("video", source.getStream()?.bufferedReader()?.use { it.readText() })
            assertEquals(3, calls)
        }

    @Test fun persistentTimeoutStopsAfterThreeAttempts() =
        runBlocking {
            var calls = 0
            install {
                calls++
                throw SocketTimeoutException("offline")
            }
            assertNull(NetworkDanmuSource("https://fixture.test/video.mkv").getStream())
            assertEquals(3, calls)
        }

    @Test fun cancellationPropagatesWithoutRetry() =
        runBlocking {
            var calls = 0
            val cancellation = CancellationException("cancel")
            install {
                calls++
                throw cancellation
            }
            val failure = runCatching { NetworkDanmuSource("https://fixture.test/video.mkv").getStream() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(cancellation.message, failure?.message)
            assertEquals(1, calls)
        }

    @Test fun nonRetryableFailureDoesNotRepeatRequest() =
        runBlocking {
            var calls = 0
            install {
                calls++
                throw IllegalArgumentException("invalid request")
            }
            assertNull(NetworkDanmuSource("https://fixture.test/video.mkv").getStream())
            assertEquals(1, calls)
        }

    private fun install(call: () -> Any) {
        val service =
            Proxy.newProxyInstance(ExtendedService::class.java.classLoader, arrayOf(ExtendedService::class.java)) { _, method, args ->
                check(method.name == "getResourceResponseBody")
                try {
                    call()
                } catch (error: Exception) {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args.last() as Continuation<Any?>
                    continuation.resumeWith(Result.failure(error))
                    COROUTINE_SUSPENDED
                }
            } as ExtendedService
        val provider =
            Proxy.newProxyInstance(
                RetrofitServiceProvider::class.java.classLoader,
                arrayOf(RetrofitServiceProvider::class.java)
            ) { _, method, _ ->
                check(method.name == "getExtendedService")
                service
            } as RetrofitServiceProvider
        RetrofitManager.replaceServiceProvider(provider)
    }
}
