package com.xyoye.common_component.storage

import com.xyoye.common_component.network.request.NetworkException
import com.xyoye.common_component.storage.baidupan.play.BaiduPanDlinkCache
import com.xyoye.common_component.storage.cloud115.play.Cloud115DownUrlCache
import com.xyoye.common_component.storage.open115.play.Open115DownUrlCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

@RunWith(Parameterized::class)
class PlaybackLinkCacheFailureTest(
    private val kind: String
) {
    private val cloud = Cloud115DownUrlCache()
    private val open = Open115DownUrlCache()
    private val baidu = BaiduPanDlinkCache()

    @Test fun cancellationAndNonNetworkFailuresPropagateWithStaleCache() =
        runBlocking {
            seed()
            for (failure in listOf(
                CancellationException("cancel"),
                IllegalStateException("bug"),
                NetworkException(401, "unauthorized", IOException()),
                SSLHandshakeException("certificate"),
                NetworkException(1004, "certificate", SSLHandshakeException("certificate"))
            )) {
                assertSame(failure, runCatching { resolve { throw failure } }.exceptionOrNull())
            }
        }

    @Test fun transientNetworkFailureCanUseStaleCache() =
        runBlocking {
            val stale = seed()
            assertEquals(stale, resolve { throw IOException("offline") })
            assertEquals(stale, resolve { throw NetworkException(1003, "timeout", IOException("timeout")) })
        }

    @Test fun networkFailureWithoutCachePropagates() =
        runBlocking {
            val failure = IOException("offline")
            assertSame(failure, runCatching { resolve { throw failure } }.exceptionOrNull())
        }

    @Test fun successfulRefreshReplacesStaleCache() =
        runBlocking {
            seed()
            val fresh = entry(System.currentTimeMillis())
            assertEquals(fresh, resolve { fresh })
            assertEquals(fresh, resolve { throw IOException("offline") })
        }

    private fun entry(time: Long): Any =
        when (kind) {
            "cloud" -> Cloud115DownUrlCache.Entry("1", "pick", "https://fixture.test/$time", "agent", 10, time)
            "open" -> Open115DownUrlCache.Entry("1", "pick", "https://fixture.test/$time", "agent", 10, time)
            else -> BaiduPanDlinkCache.Entry(1, "https://fixture.test/$time", time, 10)
        }

    private fun seed(): Any =
        entry(1).also {
            when (it) {
                is Cloud115DownUrlCache.Entry -> cloud.put(it)
                is Open115DownUrlCache.Entry -> open.put(it)
                is BaiduPanDlinkCache.Entry -> baidu.put(it)
            }
        }

    private suspend fun resolve(loader: suspend () -> Any): Any =
        when (kind) {
            "cloud" -> cloud.resolve("1", true) { loader() as Cloud115DownUrlCache.Entry }
            "open" -> open.resolve("1", true) { loader() as Open115DownUrlCache.Entry }
            else -> baidu.resolve(1, true) { loader() as BaiduPanDlinkCache.Entry }
        }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<String> = listOf("cloud", "open", "baidu")
    }
}
