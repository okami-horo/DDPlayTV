package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.storage.file.helper.SmbPlayServer
import com.xyoye.common_component.storage.file.impl.SmbStorageFile
import com.xyoye.common_component.storage.impl.SmbConnection
import com.xyoye.common_component.storage.impl.SmbFileInfo
import com.xyoye.common_component.storage.impl.SmbStorage
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SmbPlaybackTransportTest {
    private val library = MediaLibraryEntity(id = 10, displayName = "SMB", mediaType = MediaType.SMB_SERVER, url = "test-host")
    private val profile = PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.GLOBAL)
    private val browserConnection = RecordingConnection("brows")
    private val browser = SmbStorage(library, browserConnection)
    private val input = SmbStorageFile(browser, "videos", "episode.mkv", 5, false)
    private val connections = mutableListOf<RecordingConnection>()
    private val servers = mutableListOf<SmbPlayServer>()
    private val leases = mutableListOf<PlaybackTransportLease>()

    @After
    fun closeFixtures() {
        leases.forEach { it.release() }
        servers.forEach { it.release() }
        browser.close()
    }

    @Test
    fun borrowedProxyUrlCannotAcquireAnUnownedDirectLease() =
        runBlocking {
            val active = acquire(factory())
            val failure = runCatching { directPlaybackTransport(active.lease.playUrl) }.exceptionOrNull()
            assertTrue("An SMB proxy URL must retain its original owner", failure is TransportAcquisitionException)
            assertEquals(TransportAcquisitionFailure.UnsupportedTransport, (failure as TransportAcquisitionException).reason)
            assertEquals("first", read(active.lease.playUrl))
        }

    @Test
    fun closingBrowserStorageDoesNotStopAnotherPlaybackServer() =
        runBlocking {
            val factory = factory()
            val first = acquire(factory)
            val second = acquire(factory)
            browser.close()
            assertEquals("first", read(first.lease.playUrl))
            assertEquals("other", read(second.lease.playUrl))
            assertTrue(servers.all { it.isAlive })
            assertEquals(1, browserConnection.closes.get())
            assertTrue(connections.all { it.closes.get() == 0 })
        }

    @Test
    fun sameFileAcquisitionsHaveSeparateConnectionsAndSurviveEitherRetirement() =
        runBlocking {
            for (retireFirst in listOf(true, false)) {
                connections.clear()
                val factory = factory()
                val first = acquire(factory)
                val second = acquire(factory)
                assertNotSame(input, first.playbackFile)
                assertNotSame(first.playbackFile.storage, second.playbackFile.storage)
                assertSame(browser, input.storage)
                assertNotSame(browser, first.playbackFile.storage)
                assertEquals(listOf("videos/episode.mkv"), connections[0].resolvedPaths)
                assertEquals(listOf("videos/episode.mkv"), connections[1].resolvedPaths)
                assertEquals(input.uniqueKey(), first.playbackFile.uniqueKey())
                assertEquals("videos/episode.mkv", second.playbackFile.storagePath())
                assertEquals("irs", read(first.lease.playUrl, "bytes=1-3"))
                assertEquals("the", read(second.lease.playUrl, "bytes=1-3"))
                val retired = if (retireFirst) first else second
                val live = if (retireFirst) second else first
                val retiredIndex = if (retireFirst) 0 else 1
                val liveIndex = 1 - retiredIndex
                retired.lease.release()
                retired.lease.release()
                assertEquals(1, connections[retiredIndex].closes.get())
                assertEquals(0, connections[liveIndex].closes.get())
                assertEquals(if (retireFirst) "other" else "first", read(live.lease.playUrl))
                assertEquals(listOf("episode.mkv"), browser.listFiles(SmbStorageFile(browser, "different", "")).map { it.fileName() })
                assertEquals("different", browserConnection.share)
                assertEquals("videos", connections[liveIndex].share)
                assertEquals(0, browserConnection.closes.get())
                live.lease.release()
            }
        }

    @Test
    fun startIsIdempotentAndRetiredConnectionCannotReopen() =
        runBlocking {
            val acquired = acquire(factory())
            val server = servers.single()
            val url = acquired.lease.playUrl
            assertTrue(server.startSync())
            assertEquals(url, server.playUrl())
            assertEquals("first", read(url))
            acquired.lease.release()
            assertFalse(server.startSync())
            assertNull(acquired.playbackFile.storage.openFile(acquired.playbackFile))
            assertNull(acquired.playbackFile.storage.pathFile("videos/episode.mkv", false))
            assertEquals(1, connections.single().opens.get())
            assertEquals(1, connections.single().closes.get())
        }

    @Test
    fun failureAtEachAcquisitionBoundaryClosesOnlyCandidate() =
        runBlocking {
            verifyAcquisitionFailures(cancel = false)
        }

    @Test
    fun cancellationAtEachAcquisitionBoundaryPropagatesAndClosesOnlyCandidate() =
        runBlocking {
            verifyAcquisitionFailures(cancel = true)
        }

    private suspend fun verifyAcquisitionFailures(cancel: Boolean) {
        val active = acquire(factory())
        for (stage in listOf("connect", "stat", "server", "start", "url")) {
            val candidate = RecordingConnection("other")

            fun fail(): Nothing = if (cancel) throw CancellationException(stage) else throw IOException(stage)
            if (stage == "connect") candidate.beforeSelect = { fail() }
            if (stage == "stat") candidate.beforeStat = { fail() }
            val factory =
                PlaybackTransportFactory(createStorage = { config ->
                    SmbStorage(config, candidate) { storage, file ->
                        if (stage == "server") fail()
                        object : SmbPlayServer(storage, file) {
                            override suspend fun startSync(): Boolean {
                                val started = super.startSync()
                                if (stage == "start") fail()
                                return started
                            }

                            override fun playUrl(): String {
                                val url = super.playUrl()
                                if (stage == "url") fail()
                                return url
                            }
                        }.also { servers.add(it) }
                    }
                })
            val outcome = runCatching { factory.acquire(browser, input, profile) }
            if (cancel) {
                assertTrue(outcome.exceptionOrNull() is CancellationException)
            } else {
                assertTrue(outcome.getOrThrow() is TransportAcquisitionResult.Failure)
            }
            assertEquals("Cleanup at $stage", 1, candidate.closes.get())
            assertEquals(0, browserConnection.closes.get())
            assertEquals("first", read(active.lease.playUrl))
            assertEquals(1, servers.count { it.isAlive })
            assertEquals(1, browser.listFiles(SmbStorageFile(browser, "videos", "")).size)
        }
    }

    @Test
    fun occupiedPortFailsWithoutClosingExistingPlayback() =
        runBlocking {
            val active = acquire(factory())
            val port = servers.single().listeningPort
            val factory = factory { storage, file -> SmbPlayServer(storage, file, port) }
            val failed = factory.acquire(browser, input, profile) as TransportAcquisitionResult.Failure
            assertEquals(TransportAcquisitionFailure.ServerStartFailed, failed.reason)
            assertEquals(1, connections.last().closes.get())
            assertEquals("first", read(active.lease.playUrl))
        }

    @Test
    fun directoriesAreRejectedBeforeStartingAServer() =
        runBlocking {
            val connection = RecordingConnection("other").apply { directory = true }
            val factory = PlaybackTransportFactory(createStorage = { SmbStorage(it, connection) })
            val failed = factory.acquire(browser, input, profile) as TransportAcquisitionResult.Failure
            assertEquals(TransportAcquisitionFailure.UpstreamUnavailable, failed.reason)
            assertEquals(1, connection.closes.get())
            assertEquals(0, connection.opens.get())
        }

    @Test
    fun retirementWaitsForAnOpeningStreamWithoutBlockingOtherLeases() =
        runBlocking {
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val retiring = CountDownLatch(1)
            val factory =
                factory { storage, file ->
                    object : SmbPlayServer(storage, file) {
                        override fun stop() {
                            retiring.countDown()
                            super.stop()
                        }
                    }
                }
            val first = acquire(factory)
            val second = acquire(factory)
            connections[0].beforeOpen = {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val request = executor.submit { runCatching { read(first.lease.playUrl) } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val release = executor.submit { first.lease.release() }
                assertTrue(retiring.await(5, TimeUnit.SECONDS))
                assertEquals(0, connections[0].closes.get())
                assertEquals("other", read(second.lease.playUrl))
                resume.countDown()
                release.get(5, TimeUnit.SECONDS)
                request.get(5, TimeUnit.SECONDS)
                assertEquals(1, connections[0].closes.get())
                assertEquals(listOf(1), connections[0].streamCloses.map { it.get() })
                assertNull(first.playbackFile.storage.openFile(first.playbackFile))
                assertEquals(1, connections[0].opens.get())
            } finally {
                resume.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun streamCleanupFailureDoesNotSkipConnectionCleanupOrCloseTwice() =
        runBlocking {
            val acquired = acquire(factory())
            val connection = connections.single().apply { failStreamClose = true }
            val response = servers.single().serve(session(URL(acquired.lease.playUrl).path))
            acquired.lease.release()
            acquired.lease.release()
            response.close()
            assertEquals(listOf(1), connection.streamCloses.map { it.get() })
            assertEquals(1, connection.closes.get())
            assertFalse(servers.single().isAlive)
        }

    private fun factory(
        createServer: (SmbStorage, SmbStorageFile) -> SmbPlayServer = { storage, file -> SmbPlayServer(storage, file) }
    ): PlaybackTransportFactory =
        PlaybackTransportFactory(createStorage = { config ->
            val connection = RecordingConnection(if (connections.isEmpty()) "first" else "other")
            connections.add(connection)
            SmbStorage(config, connection) { storage, file -> createServer(storage, file).also { servers.add(it) } }
        })

    private suspend fun acquire(factory: PlaybackTransportFactory): AcquiredPlaybackTransport {
        val result = factory.acquire(browser, input, profile)
        assertTrue("Acquisition must succeed: $result", result is TransportAcquisitionResult.Success)
        return (result as TransportAcquisitionResult.Success).transport.also { leases.add(it.lease) }
    }

    private fun read(
        url: String,
        range: String? = null
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        range?.let { connection.setRequestProperty("Range", it) }
        return try {
            assertEquals(if (range == null) 200 else 206, connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun session(path: String): NanoHTTPD.IHTTPSession =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(NanoHTTPD.IHTTPSession::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getUri" -> path
                "getHeaders" -> emptyMap<String, String>()
                else -> error("Unexpected session operation: ${method.name}")
            }
        } as NanoHTTPD.IHTTPSession

    private class RecordingConnection(
        private val text: String
    ) : SmbConnection {
        val closes = AtomicInteger()
        val opens = AtomicInteger()
        val streamCloses = mutableListOf<AtomicInteger>()
        val resolvedPaths = mutableListOf<String>()
        var share = ""
        var directory = false
        var failStreamClose = false
        var beforeSelect: () -> Unit = {}
        var beforeStat: () -> Unit = {}
        var beforeOpen: () -> Unit = {}

        override fun shares(): List<String> {
            check(closes.get() == 0)
            return listOf("videos")
        }

        override fun selectShare(name: String) {
            check(closes.get() == 0)
            beforeSelect()
            share = name
        }

        override fun stat(path: String): SmbFileInfo {
            check(closes.get() == 0)
            beforeStat()
            resolvedPaths.add("$share/$path")
            return SmbFileInfo(text.length.toLong(), directory)
        }

        override fun list(path: String): List<String> {
            check(closes.get() == 0)
            return listOf("episode.mkv")
        }

        override fun open(path: String): InputStream {
            check(closes.get() == 0)
            beforeOpen()
            opens.incrementAndGet()
            val closeCount = AtomicInteger().also { streamCloses.add(it) }
            return object : ByteArrayInputStream(text.toByteArray()) {
                override fun close() {
                    closeCount.incrementAndGet()
                    if (failStreamClose) throw IOException("stream close failed")
                    super.close()
                }
            }
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }
}
