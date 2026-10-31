package com.xyoye.common_component.network.tmdb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class TmdbClientTest {
    @Test fun authenticationAndMissingEntriesDoNotRetryOrBecomeEmptyResults() =
        runTest {
            for ((status, category) in listOf(401 to "AUTHENTICATION", 403 to "AUTHENTICATION", 404 to "NOT_FOUND")) {
                var calls = 0
                try {
                    TmdbClient.request<Unit> {
                        calls++
                        throw http(status)
                    }
                    fail()
                } catch (error: TmdbFailure) {
                    assertEquals(category, error.category)
                    assertEquals(1, calls)
                }
            }
        }

    @Test fun serverAndNetworkRetriesAreBounded() =
        runTest {
            for ((failure, category) in listOf(http(503) to "SERVER", IOException("offline") to "NETWORK")) {
                var calls = 0
                try {
                    TmdbClient.request<Unit> {
                        calls++
                        throw failure
                    }
                    fail()
                } catch (error: TmdbFailure) {
                    assertEquals(category, error.category)
                    assertEquals(2, calls)
                }
            }
        }

    @Test fun longServerCooldownIsSurfacedWithoutPrematureRetry() =
        runTest {
            var calls = 0
            val response =
                okhttp3.Response
                    .Builder()
                    .request(
                        okhttp3.Request
                            .Builder()
                            .url("https://api.themoviedb.org/3/configuration")
                            .build()
                    ).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(429)
                    .message("Limited")
                    .header("Retry-After", "120")
                    .build()
            try {
                TmdbClient.request<Unit> {
                    calls++
                    throw HttpException(Response.error<Unit>("".toResponseBody(), response))
                }
                fail()
            } catch (error: TmdbFailure) {
                assertEquals("RATE_LIMIT", error.category)
                assertEquals(120_000L, error.retryAfterMs)
                assertEquals(1, calls)
            }
        }

    @Test fun cancellationIsPropagated() =
        runTest {
            val cancelled = CancellationException("caller left")
            try {
                TmdbClient.request<Unit> { throw cancelled }
                fail()
            } catch (actual: CancellationException) {
                assertSame(cancelled, actual)
            }
        }

    private fun http(status: Int) = HttpException(Response.error<Unit>(status, "".toResponseBody()))
}
