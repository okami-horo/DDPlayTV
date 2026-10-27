package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in wire tests against the loopback fixtures documented in the lease plan. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackTransportProtocolIntegrationTest {
    private val profile = PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.GLOBAL)
    private val payload = ByteArray(256 * 1024) { ((it * 31 + 7) % 251).toByte() }

    @Test
    fun smbDefaultFactoryKeepsBrowserAndBothPlaybackConnectionsIndependent() =
        runBlocking {
            assumeTrue("Requires local SMB/FTP fixtures", System.getenv("DDPLAYTV_LEASE_FIXTURES") == "1")
            verifyWireIsolation(
                MediaLibraryEntity(
                    displayName = "SMB fixture",
                    mediaType = MediaType.SMB_SERVER,
                    url = "127.0.0.1",
                    port = 1445,
                    account = "lease",
                    password = "lease-fixture",
                    smbSharePath = "videos"
                ),
                "videos/probe.bin"
            )
        }

    @Test
    fun ftpDefaultFactoryKeepsBrowserAndBothPlaybackConnectionsIndependent() =
        runBlocking {
            assumeTrue("Requires local SMB/FTP fixtures", System.getenv("DDPLAYTV_LEASE_FIXTURES") == "1")
            verifyWireIsolation(
                MediaLibraryEntity(
                    displayName = "FTP fixture",
                    mediaType = MediaType.FTP_SERVER,
                    url = "ftp://127.0.0.1:2121",
                    ftpAddress = "127.0.0.1",
                    port = 2121,
                    account = "lease",
                    password = "lease-fixture",
                    ftpEncoding = "UTF-8"
                ),
                "/probe.bin"
            )
        }

    private suspend fun verifyWireIsolation(
        library: MediaLibraryEntity,
        path: String
    ) {
        for (retireFirst in listOf(true, false)) {
            val browser = checkNotNull(StorageFactory.createStorage(library))
            val leases = mutableListOf<PlaybackTransportLease>()
            try {
                val file = checkNotNull(browser.pathFile(path, false))

                suspend fun acquire(): AcquiredPlaybackTransport {
                    val result = PlaybackTransportFactory().acquire(browser, file, profile)
                    assertTrue("Real factory acquisition failed: $result", result is TransportAcquisitionResult.Success)
                    return (result as TransportAcquisitionResult.Success).transport.also { leases.add(it.lease) }
                }
                val a = acquire()
                val b = acquire()
                assertNotSame(browser, a.playbackFile.storage)
                assertNotSame(a.playbackFile.storage, b.playbackFile.storage)
                coroutineScope {
                    val first = async(Dispatchers.IO) { read(a.lease.playUrl) }
                    val second = async(Dispatchers.IO) { read(b.lease.playUrl) }
                    assertBrowserAvailable(browser)
                    assertArrayEquals(payload, first.await())
                    assertArrayEquals(payload, second.await())
                }
                val retired = if (retireFirst) a else b
                val survivor = if (retireFirst) b else a
                retired.lease.release()
                retired.lease.release()
                assertNull(retired.playbackFile.storage.openFile(retired.playbackFile))
                assertArrayEquals(payload, read(survivor.lease.playUrl))
                assertBrowserAvailable(browser)
                browser.close()
                assertArrayEquals(payload, read(survivor.lease.playUrl))
            } finally {
                leases.forEach { it.release() }
                browser.close()
            }
        }
    }

    private suspend fun assertBrowserAvailable(browser: Storage) {
        val root = checkNotNull(browser.getRootFile())
        assertTrue(browser.openDirectory(root, true).any { it.fileName() == "probe.bin" })
    }

    private fun read(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        return try {
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
