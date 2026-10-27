package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.storage.file.helper.FtpPlayServer
import com.xyoye.common_component.storage.file.impl.FtpStorageFile
import com.xyoye.common_component.storage.impl.FtpStorage
import com.xyoye.common_component.storage.impl.LinkStorage
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class FtpPlaybackTransportTest {
    private val library =
        MediaLibraryEntity(
            id = 20,
            displayName = "FTP",
            mediaType = MediaType.FTP_SERVER,
            url = "ftp://test-host",
            ftpAddress = "test-host",
            port = 21,
            account = "test-account",
            password = "test-password",
            ftpEncoding = "UTF-8"
        )
    private val profile = PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.GLOBAL)
    private val browserClient = RecordingClient("brows")
    private val browser = FtpStorage(library, browserClient)
    private val input = FtpStorageFile(browser, "/shows", metadata())
    private val clients = mutableListOf<RecordingClient>()
    private val servers = mutableListOf<FtpPlayServer>()
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
            val link =
                LinkStorage(MediaLibraryEntity(displayName = "borrowed", url = active.lease.playUrl, mediaType = MediaType.STREAM_LINK))
            val result = PlaybackTransportFactory().acquire(link, link.getRootFile(), profile)
            assertTrue("An FTP proxy URL must retain its original owner", result is TransportAcquisitionResult.Failure)
            assertEquals(TransportAcquisitionFailure.UnsupportedTransport, (result as TransportAcquisitionResult.Failure).reason)
            assertEquals("first", read(active.lease.playUrl).second)
        }

    @Test
    fun preparingAnotherSourceDoesNotReplaceTheFirstSource() =
        runBlocking {
            val factory = factory()
            val first = acquire(factory)
            val second = acquire(factory)
            assertNotSame(input, first.playbackFile)
            assertNotSame(first.playbackFile.storage, second.playbackFile.storage)
            assertSame(browser, input.storage)
            assertEquals(input.storagePath(), first.playbackFile.storagePath())
            assertEquals(input.uniqueKey(), first.playbackFile.uniqueKey())
            assertEquals(listOf("/shows/episode.mkv"), clients[0].statPaths)
            assertEquals(listOf("/shows/episode.mkv"), clients[1].statPaths)
            assertEquals("first", read(first.lease.playUrl).second)
            assertEquals("other", read(second.lease.playUrl).second)
            assertEquals("first", read(first.lease.playUrl).second)
            assertEquals(listOf("test-account", "test-password"), clients[0].loginValues)
            assertEquals(FTP.BINARY_FILE_TYPE, clients[0].configuredType)
            assertEquals("UTF-8", clients[0].controlEncoding)
            assertEquals(FTPClient.PASSIVE_LOCAL_DATA_CONNECTION_MODE, clients[0].dataConnectionMode)
            browser.close()
            assertEquals("first", read(first.lease.playUrl).second)
            assertEquals("other", read(second.lease.playUrl).second)
        }

    @Test
    fun eitherLeaseCanCloseWithoutChangingBrowserOrOtherConnection() =
        runBlocking {
            for (retireFirst in listOf(true, false)) {
                clients.clear()
                val factory = factory()
                val first = acquire(factory)
                val second = acquire(factory)
                val retired = if (retireFirst) first else second
                val live = if (retireFirst) second else first
                val retiredIndex = if (retireFirst) 0 else 1
                val liveIndex = 1 - retiredIndex
                assertEquals(
                    "irs",
                    first.playbackFile.storage.let { (it as FtpStorage).openFile(first.playbackFile, 1)!! }.use {
                        ByteArray(3).also { bytes -> it.read(bytes) }.toString(Charsets.UTF_8)
                    }
                )
                assertEquals(listOf(1L), clients[0].offsets)
                assertEquals("other", read(second.lease.playUrl, "bytes=1-3").second)
                assertEquals(listOf(0L), clients[1].offsets)
                retired.lease.release()
                retired.lease.release()
                assertEquals(1, clients[retiredIndex].disconnects.get())
                assertEquals(0, clients[liveIndex].disconnects.get())
                assertEquals(if (retireFirst) "other" else "first", read(live.lease.playUrl).second)
                assertEquals(1, browser.listFiles(browser.getRootFile()).size)
                assertEquals(0, browserClient.disconnects.get())
                live.lease.release()
            }
        }

    @Test
    fun syntheticBrowserMetadataCannotBecomeAPlaybackFile() =
        runBlocking {
            browserClient.missing = true
            assertNotNull(browser.pathFile("/shows/missing.mkv", false))
            val client = RecordingClient("other").apply { missing = true }
            val factory = PlaybackTransportFactory(createStorage = { FtpStorage(it, client) })
            val result = factory.acquire(browser, input, profile) as TransportAcquisitionResult.Failure
            assertEquals(TransportAcquisitionFailure.UpstreamUnavailable, result.reason)
            assertEquals(1, client.disconnects.get())
            assertEquals(0, client.opens.get())
            assertEquals(0, browserClient.disconnects.get())
        }

    @Test
    fun serversWithoutMlstCanResolveARealMatchingListEntry() =
        runBlocking {
            val client = RecordingClient("first").apply { beforeStat = { throw IOException("MLST unsupported") } }
            val factory = PlaybackTransportFactory(createStorage = { FtpStorage(it, client) })
            val acquired = acquire(factory)
            assertEquals(input.storagePath(), acquired.playbackFile.storagePath())
            assertEquals("first", read(acquired.lease.playUrl).second)
            assertEquals(listOf("/shows"), client.listPaths)
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
            val candidate = RecordingClient("other")

            fun fail(): Nothing = if (cancel) throw CancellationException(stage) else throw IOException(stage)
            if (stage == "connect") candidate.beforeConnect = { fail() }
            if (stage == "stat") {
                candidate.beforeStat = { fail() }
                candidate.beforeList = { fail() }
            }
            val factory =
                PlaybackTransportFactory(createStorage = { config ->
                    FtpStorage(config, candidate) { storage, file ->
                        if (stage == "server") fail()
                        object : FtpPlayServer(storage, file) {
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
            assertEquals("Cleanup at $stage", 1, candidate.disconnects.get())
            assertEquals(0, browserClient.disconnects.get())
            assertEquals("first", read(active.lease.playUrl).second)
            assertEquals(1, servers.count { it.isAlive })
            assertEquals(1, browser.listFiles(browser.getRootFile()).size)
        }
    }

    @Test
    fun occupiedPortFailsWithoutClosingExistingPlayback() =
        runBlocking {
            val active = acquire(factory())
            val port = servers.single().listeningPort
            val failed = factory { storage, file -> FtpPlayServer(storage, file, port) }.acquire(browser, input, profile)
            assertEquals(TransportAcquisitionFailure.ServerStartFailed, (failed as TransportAcquisitionResult.Failure).reason)
            assertEquals(1, clients.last().disconnects.get())
            assertEquals("first", read(active.lease.playUrl).second)
        }

    @Test
    fun failedLoginAndDirectoryMetadataDoNotStartPlayback() =
        runBlocking {
            for (failedLogin in listOf(true, false)) {
                val client =
                    RecordingClient("other").apply {
                        loginSucceeds = !failedLogin
                        directory = !failedLogin
                    }
                val factory = PlaybackTransportFactory(createStorage = { FtpStorage(it, client) })
                assertTrue(factory.acquire(browser, input, profile) is TransportAcquisitionResult.Failure)
                assertEquals(1, client.disconnects.get())
                assertEquals(0, client.opens.get())
            }
        }

    @Test
    fun lateCloseOfOldStreamCannotCompleteTheNewTransfer() =
        runBlocking {
            val acquired = acquire(factory())
            val storage = acquired.playbackFile.storage as FtpStorage
            val first = storage.openFile(acquired.playbackFile)!!
            val second = storage.openFile(acquired.playbackFile, 2)!!
            val client = clients.single()
            assertEquals(1, client.completions.get())
            first.close()
            assertEquals(1, client.completions.get())
            assertEquals("rst", second.bufferedReader().readText())
            assertEquals(listOf(0L, 2L), client.offsets)
            second.close()
            storage.completePending()
            assertEquals(2, client.completions.get())
            assertEquals(listOf(1, 1), client.streamCloses.map { it.get() })
            acquired.lease.release()
            assertEquals(1, client.disconnects.get())
            assertNull(storage.openFile(acquired.playbackFile))
            assertFalse(servers.single().startSync())
            assertEquals(2, client.opens.get())
        }

    @Test
    fun retirementCompletesPendingEvenIfStreamCloseOrPendingCommandFails() =
        runBlocking {
            for (streamFailure in listOf(true, false)) {
                clients.clear()
                val acquired = acquire(factory())
                val client =
                    clients.single().apply {
                        failStreamClose = streamFailure
                        failComplete = !streamFailure
                    }
                val server = servers.last()
                val response = server.serve(session(URL(acquired.lease.playUrl).path))
                acquired.lease.release()
                acquired.lease.release()
                response.close()
                assertEquals(listOf(1), client.streamCloses.map { it.get() })
                assertEquals(1, client.completions.get())
                assertEquals(1, client.disconnects.get())
                assertFalse(server.isAlive)
            }
        }

    @Test
    fun retirementWaitsForOpeningTransferWithoutClosingOtherClients() =
        runBlocking {
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val retiring = CountDownLatch(1)
            val factory =
                factory { storage, file ->
                    object : FtpPlayServer(storage, file) {
                        override fun stop() {
                            retiring.countDown()
                            super.stop()
                        }
                    }
                }
            val first = acquire(factory)
            val second = acquire(factory)
            clients[0].beforeOpen = {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val request = executor.submit { runCatching { read(first.lease.playUrl) } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val release = executor.submit { first.lease.release() }
                assertTrue(retiring.await(5, TimeUnit.SECONDS))
                assertEquals(0, clients[0].disconnects.get())
                assertEquals("other", read(second.lease.playUrl).second)
                resume.countDown()
                release.get(5, TimeUnit.SECONDS)
                request.get(5, TimeUnit.SECONDS)
                assertEquals(1, clients[0].disconnects.get())
                assertEquals(1, clients[0].completions.get())
                assertEquals(listOf(1), clients[0].streamCloses.map { it.get() })
                assertNull(first.playbackFile.storage.openFile(first.playbackFile))
                assertEquals(1, clients[0].opens.get())
            } finally {
                resume.countDown()
                executor.shutdownNow()
            }
        }

    private fun factory(
        createServer: (FtpStorage, FtpStorageFile) -> FtpPlayServer = { storage, file -> FtpPlayServer(storage, file) }
    ): PlaybackTransportFactory =
        PlaybackTransportFactory(createStorage = { config ->
            val client = RecordingClient(if (clients.isEmpty()) "first" else "other")
            clients.add(client)
            FtpStorage(config, client) { storage, file -> createServer(storage, file).also { servers.add(it) } }
        })

    private suspend fun acquire(factory: PlaybackTransportFactory): AcquiredPlaybackTransport {
        val result = factory.acquire(browser, input, profile)
        assertTrue("Acquisition must succeed: $result", result is TransportAcquisitionResult.Success)
        return (result as TransportAcquisitionResult.Success).transport.also { leases.add(it.lease) }
    }

    private fun read(
        url: String,
        range: String? = null
    ): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        range?.let { connection.setRequestProperty("Range", it) }
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            status to stream.bufferedReader().use { it.readText() }
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

    private class RecordingClient(
        private val text: String
    ) : FTPClient() {
        val disconnects = AtomicInteger()
        val completions = AtomicInteger()
        val opens = AtomicInteger()
        val streamCloses = mutableListOf<AtomicInteger>()
        val statPaths = mutableListOf<String>()
        val listPaths = mutableListOf<String>()
        val offsets = mutableListOf<Long>()
        var loginValues = emptyList<String>()
        var configuredType = -1
        var connected = false
        var missing = false
        var directory = false
        var loginSucceeds = true
        var failStreamClose = false
        var failComplete = false
        var beforeConnect: () -> Unit = {}
        var beforeStat: () -> Unit = {}
        var beforeList: () -> Unit = {}
        var beforeOpen: () -> Unit = {}

        override fun isAvailable(): Boolean = connected

        override fun isConnected(): Boolean = connected

        override fun connect(
            hostname: String,
            port: Int
        ) {
            connected = true
            beforeConnect()
        }

        override fun login(
            username: String?,
            password: String?
        ): Boolean {
            loginValues = listOf(username.orEmpty(), password.orEmpty())
            return loginSucceeds
        }

        override fun setFileType(type: Int): Boolean {
            configuredType = type
            return true
        }

        override fun printWorkingDirectory(): String = "/"

        override fun mlistFile(path: String): FTPFile? {
            check(connected)
            beforeStat()
            statPaths.add(path)
            if (missing) return null
            return metadata().apply {
                name = path
                if (directory) type = FTPFile.DIRECTORY_TYPE
            }
        }

        override fun listFiles(path: String): Array<FTPFile> {
            check(connected)
            beforeList()
            listPaths.add(path)
            return if (missing) emptyArray() else arrayOf(metadata())
        }

        override fun retrieveFileStream(path: String): InputStream {
            check(connected)
            beforeOpen()
            opens.incrementAndGet()
            val offset = restartOffset
            offsets.add(offset)
            restartOffset = 0
            val closeCount = AtomicInteger().also { streamCloses.add(it) }
            return object : ByteArrayInputStream(text.drop(offset.toInt()).toByteArray()) {
                override fun close() {
                    closeCount.incrementAndGet()
                    if (failStreamClose) throw IOException("stream close failed")
                    super.close()
                }
            }
        }

        override fun completePendingCommand(): Boolean {
            completions.incrementAndGet()
            if (failComplete) throw IOException("pending command failed")
            return true
        }

        override fun disconnect() {
            disconnects.incrementAndGet()
            connected = false
        }
    }

    private companion object {
        fun metadata(): FTPFile =
            FTPFile().apply {
                name = "episode.mkv"
                size = 5
                type = FTPFile.FILE_TYPE
            }
    }
}
