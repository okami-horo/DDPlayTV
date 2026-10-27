package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.network.service.ExtendedService
import com.xyoye.common_component.storage.file.helper.HttpPlayServer
import com.xyoye.common_component.storage.file.helper.LocalProxy
import com.xyoye.data_component.enums.LocalProxyMode
import com.xyoye.data_component.enums.PlayerType
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HttpPlaybackTransportTest {
    private val proxy = HttpPlayServer()
    private val upstreams = mutableListOf<TextUpstream>()
    private val leases = mutableListOf<PlaybackTransportLease>()

    @After
    fun closeFixtures() {
        leases.forEach { it.release() }
        proxy.stop()
        HttpPlayServer.getInstance().stop()
        upstreams.forEach { it.stop() }
    }

    @Test
    fun preparingSecondUpstreamDoesNotChangeFirstPlaybackUrl() =
        runBlocking {
            val firstUpstream = upstream("first")
            val secondUpstream = upstream("other")
            val headers = mutableMapOf("Authorization" to "first-secret", "Cookie" to "first-cookie")
            val first = acquire(firstUpstream, headers)
            val second = acquire(secondUpstream, mapOf("Authorization" to "other-secret"))
            headers["Authorization"] = "changed-after-acquisition"
            assertEquals("first", read(first.playUrl).text)
            assertEquals("other", read(second.playUrl).text)
            assertEquals("first-secret", firstUpstream.requests.single()["authorization"])
            assertEquals("first-cookie", firstUpstream.requests.single()["cookie"])
            assertEquals("other-secret", secondUpstream.requests.single()["authorization"])
            assertFalse(first.playUrl.contains("secret"))
            assertTrue(first.playUrl.contains("/lease/${first.leaseId}/"))
            first.release()
            first.release()
            assertEquals(404, read(first.playUrl).status)
            assertEquals("other", read(second.playUrl).text)
            assertFalse(second.isReleased)
            assertFalse(proxy.isServingUrl(first.playUrl))
            assertEquals(404, read(second.playUrl.replace(second.leaseId, "unknown")).status)
        }

    @Test
    fun seekAndRangeStateBelongToTheSelectedLease() =
        runBlocking {
            val firstUpstream = upstream("a".repeat(3 * 1024 * 1024))
            val secondUpstream = upstream("b".repeat(3 * 1024 * 1024))
            val first = acquire(firstUpstream)
            val second = acquire(secondUpstream)
            assertTrue(proxy.setSeekEnabled(first.playUrl, true))
            val firstRead = read(first.playUrl, "bytes=0-2097151")
            val secondRead = read(second.playUrl, "bytes=0-2097151")
            assertEquals(206, firstRead.status)
            assertEquals(2 * 1024 * 1024, firstRead.bytes.size)
            assertEquals(1024 * 1024, secondRead.bytes.size)
            assertEquals("bytes=0-2097151", firstUpstream.requests.single()["range"])
            assertEquals("bytes=0-1048575", secondUpstream.requests.single()["range"])
            assertEquals(416, read(first.playUrl, "bytes=4000000-4000001").status)
            second.release()
            assertFalse(proxy.setSeekEnabled(second.playUrl, true))
            assertEquals("aa", read(first.playUrl, "bytes=0-1").text)
        }

    @Test
    fun retryAndTlsSelectionDoNotLeakIntoAnotherContext() =
        runBlocking {
            val service =
                Retrofit
                    .Builder()
                    .baseUrl("http://localhost/")
                    .client(OkHttpClient())
                    .build()
                    .create(ExtendedService::class.java)
            val policies = ConcurrentLinkedQueue<HttpPlayServer.UpstreamTlsPolicy>()
            val server =
                HttpPlayServer(serviceOverride = { policy ->
                    policies.add(policy)
                    service
                })
            val initial = upstream("stale", supportsRange = false)
            val refreshed = upstream("fresh")
            val other = upstream("other")
            val retries = AtomicInteger()
            val first =
                server.acquireTransport(source(initial), 0, "first", onRangeUnsupported = {
                    retries.incrementAndGet()
                    source(refreshed, mapOf("Authorization" to "refreshed"), HttpPlayServer.UpstreamTlsPolicy.UNSAFE_TRUST_ALL)
                })
            val second = server.acquireTransport(source(other), 0, "second")
            try {
                assertEquals("fresh", read(first.playUrl, "bytes=0-4").text)
                assertEquals("other", read(second.playUrl, "bytes=0-4").text)
                assertEquals("fre", read(first.playUrl, "bytes=0-2").text)
                assertEquals(1, retries.get())
                assertEquals(
                    listOf(
                        HttpPlayServer.UpstreamTlsPolicy.STRICT,
                        HttpPlayServer.UpstreamTlsPolicy.UNSAFE_TRUST_ALL,
                        HttpPlayServer.UpstreamTlsPolicy.STRICT,
                        HttpPlayServer.UpstreamTlsPolicy.UNSAFE_TRUST_ALL
                    ),
                    policies.toList()
                )
                assertEquals("refreshed", refreshed.requests.first()["authorization"])
                assertFalse(other.requests.single().containsKey("authorization"))
            } finally {
                first.release()
                second.release()
                server.stop()
            }
        }

    @Test
    fun retirementCancelsOnlyItsInFlightRequestAndDoesNotBlockAnotherLease() =
        runBlocking {
            val entered = CountDownLatch(1)
            val unblock = CountDownLatch(1)
            val blocked =
                upstream("first", beforeResponse = {
                    entered.countDown()
                    check(unblock.await(5, TimeUnit.SECONDS))
                })
            val first = acquire(blocked)
            val second = acquire(upstream("other"))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val pending = executor.submit<ReadResult> { read(first.playUrl) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals("other", read(second.playUrl).text)
                first.release()
                pending.get(5, TimeUnit.SECONDS)
                assertEquals(404, read(first.playUrl).status)
                assertEquals("other", read(second.playUrl).text)
            } finally {
                unblock.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun localProxyPreservesSelectionAndRejectsAnAlreadyOwnedUrl() =
        runBlocking {
            val upstream = upstream("first")

            suspend fun acquire(
                mode: LocalProxyMode,
                url: String = upstream.url
            ): PlaybackTransportLease =
                LocalProxy
                    .acquireIfNeeded(
                        PlayerType.TYPE_EXO_PLAYER,
                        mode.value,
                        url,
                        contentLength = 5,
                        prePlayRangeMinIntervalMs = 0,
                        fileName = "video",
                        autoEnabled = false
                    ).also { leases.add(it) }
            val direct = acquire(LocalProxyMode.OFF)
            assertEquals(PlaybackTransportType.DIRECT, direct.transportType)
            assertEquals(upstream.url, direct.playUrl)
            assertEquals(PlaybackTransportType.DIRECT, acquire(LocalProxyMode.AUTO).transportType)
            val proxied = acquire(LocalProxyMode.FORCE)
            assertEquals(PlaybackTransportType.HTTP_PROXY, proxied.transportType)
            assertEquals("first", read(proxied.playUrl).text)
            val nested = runCatching { acquire(LocalProxyMode.OFF, proxied.playUrl) }.exceptionOrNull()
            assertEquals(TransportAcquisitionFailure.UnsupportedTransport, (nested as TransportAcquisitionException).reason)
            val empty = runCatching { acquire(LocalProxyMode.FORCE, "") }.exceptionOrNull()
            assertEquals(TransportAcquisitionFailure.UpstreamUnavailable, (empty as TransportAcquisitionException).reason)
        }

    @Test
    fun serverStartFailureDoesNotReturnAnUnownedDirectUrl() =
        runBlocking {
            val occupied = upstream("first")
            val server = HttpPlayServer(listenPort = occupied.listeningPort)
            try {
                val failure = runCatching { server.acquireTransport(source(occupied)) }.exceptionOrNull()
                assertEquals(TransportAcquisitionFailure.ServerStartFailed, (failure as TransportAcquisitionException).reason)
                assertEquals("first", read(occupied.url).text)
            } finally {
                server.stop()
            }
        }

    private suspend fun acquire(
        upstream: TextUpstream,
        headers: Map<String, String> = emptyMap()
    ): PlaybackTransportLease = proxy.acquireTransport(source(upstream, headers), 0, "video").also { leases.add(it) }

    private fun source(
        upstream: TextUpstream,
        headers: Map<String, String> = emptyMap(),
        policy: HttpPlayServer.UpstreamTlsPolicy = HttpPlayServer.UpstreamTlsPolicy.STRICT
    ) = HttpPlayServer.UpstreamSource(upstream.url, headers, "text/plain", upstream.length, policy)

    private fun upstream(
        text: String,
        supportsRange: Boolean = true,
        beforeResponse: () -> Unit = {}
    ): TextUpstream =
        TextUpstream(text, supportsRange, beforeResponse).also {
            it.start()
            upstreams.add(it)
        }

    private fun read(
        url: String,
        range: String? = null
    ): ReadResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        range?.let { connection.setRequestProperty("Range", it) }
        return try {
            val status = connection.responseCode
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            ReadResult(status, input?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private data class ReadResult(
        val status: Int,
        val bytes: ByteArray
    ) {
        val text: String get() = bytes.toString(Charsets.UTF_8)
    }

    private class TextUpstream(
        text: String,
        private val supportsRange: Boolean,
        private val beforeResponse: () -> Unit
    ) : NanoHTTPD("127.0.0.1", 0) {
        private val bytes = text.toByteArray()
        val length: Long get() = bytes.size.toLong()
        val requests = ConcurrentLinkedQueue<Map<String, String>>()
        val url: String get() = "http://127.0.0.1:$listeningPort/video?token=test-secret"

        override fun serve(session: IHTTPSession): Response {
            requests.add(session.headers.toMap())
            beforeResponse()
            val range = session.headers["range"]?.takeIf { supportsRange }
            if (range == null) return newFixedLengthResponse(Response.Status.OK, "text/plain", ByteArrayInputStream(bytes), length)
            val parts = range.removePrefix("bytes=").split('-')
            val start = parts[0].toInt()
            val end = parts[1].toInt().coerceAtMost(bytes.lastIndex)
            val body = bytes.copyOfRange(start, end + 1)
            return newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                "text/plain",
                ByteArrayInputStream(body),
                body.size.toLong()
            ).apply {
                addHeader("Content-Range", "bytes $start-$end/$length")
            }
        }
    }
}
