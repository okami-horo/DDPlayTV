package com.xyoye.common_component.storage.transport

import android.net.Uri
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackTransportFactoryTest {
    private val profile = PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.GLOBAL)

    @Test
    fun eachSmbAndFtpAcquisitionResolvesASeparatePlaybackConnection() =
        runBlocking {
            for (type in listOf(MediaType.SMB_SERVER, MediaType.FTP_SERVER)) {
                val browser = RecordingStorage(library(type))
                val input = RecordingFile(browser, "share/episode.mkv")
                val playbackStorages = mutableListOf<RecordingStorage>()
                val factory =
                    factory { config ->
                        assertNotSame(browser.library, config)
                        assertEquals(browser.library, config)
                        RecordingStorage(config).also { playbackStorages.add(it) }
                    }

                val first = success(factory.acquire(browser, input, profile))
                val second = success(factory.acquire(browser, input, profile))
                assertNotSame(input, first.playbackFile)
                assertNotSame(first.playbackFile.storage, second.playbackFile.storage)
                assertSame(playbackStorages[0], first.playbackFile.storage)
                assertSame(playbackStorages[1], second.playbackFile.storage)
                assertEquals(listOf("share/episode.mkv"), playbackStorages[0].resolvedPaths)
                first.lease.release()
                assertEquals(1, playbackStorages[0].closes)
                assertEquals(1, (first.playbackFile as RecordingFile).closes)
                assertEquals(0, playbackStorages[1].closes)
                assertEquals(0, browser.closes)
                assertEquals(0, input.closes)
                assertFalse(second.lease.isReleased)
                second.lease.release()
            }
        }

    @Test
    fun providerFailureIsTypedAndNeverFallsBackToLegacyUrl() =
        runBlocking {
            val browser = RecordingStorage(library(MediaType.SMB_SERVER))
            val playback =
                RecordingStorage(browser.library.copy()).apply {
                    acquire = { throw TransportAcquisitionException(TransportAcquisitionFailure.ServerStartFailed) }
                }
            val result = factory { playback }.acquire(browser, RecordingFile(browser), profile)
            assertEquals(TransportAcquisitionFailure.ServerStartFailed, failure(result))
            assertEquals(1, playback.closes)
            assertEquals(1, playback.resolvedFile!!.closes)
            assertEquals(0, browser.closes)
        }

    @Test
    fun missingFileReleasesOnlyNewStorage() =
        runBlocking {
            val browser = RecordingStorage(library(MediaType.FTP_SERVER))
            val playback = RecordingStorage(browser.library.copy()).apply { resolve = { null } }
            val result = factory { playback }.acquire(browser, RecordingFile(browser), profile)
            assertEquals(TransportAcquisitionFailure.UpstreamUnavailable, failure(result))
            assertEquals(1, playback.closes)
            assertEquals(0, browser.closes)
            assertEquals(0, playback.providerCalls)
        }

    @Test
    fun mismatchedResolvedIdentityIsReleasedWithoutStartingProvider() =
        runBlocking {
            val browser = RecordingStorage(library(MediaType.SMB_SERVER))
            val playback = RecordingStorage(browser.library.copy())
            val wrong = RecordingFile(playback, "another-share/episode.mkv")
            playback.resolve = { wrong }
            val result = factory { playback }.acquire(browser, RecordingFile(browser), profile)
            assertEquals(TransportAcquisitionFailure.UpstreamUnavailable, failure(result))
            assertEquals(1, wrong.closes)
            assertEquals(1, playback.closes)
            assertEquals(0, playback.providerCalls)
        }

    @Test
    fun factoryRejectsBorrowedStorageOrFileWithoutClosingThem() =
        runBlocking {
            val browser = RecordingStorage(library(MediaType.SMB_SERVER))
            val input = RecordingFile(browser)
            val reusedStorage = factory { browser }.acquire(browser, input, profile)
            assertEquals(TransportAcquisitionFailure.ProtocolConnectionFailed, failure(reusedStorage))
            val playback = RecordingStorage(browser.library.copy()).apply { resolve = { input } }
            val reusedFile = factory { playback }.acquire(browser, input, profile)
            assertEquals(TransportAcquisitionFailure.UpstreamUnavailable, failure(reusedFile))
            assertEquals(0, browser.closes)
            assertEquals(0, input.closes)
            assertEquals(1, playback.closes)
        }

    @Test
    fun unsupportedProviderNeverUsesRawUrlOrClosesBorrowedMetadata() =
        runBlocking {
            val browser = RecordingStorage(library(MediaType.STREAM_LINK))
            val input = RecordingFile(browser)
            val factory = PlaybackTransportFactory({ error("No connection needed") }, { null }, Dispatchers.Unconfined)
            val result = factory.acquire(browser, input, profile)
            assertEquals(TransportAcquisitionFailure.UnsupportedTransport, failure(result))
            assertEquals(0, browser.closes)
            assertEquals(0, input.closes)
        }

    @Test
    fun directProviderBorrowsMetadataAndReceivesTheSelectedProfile() =
        runBlocking {
            val browser = RecordingStorage(library(MediaType.STREAM_LINK))
            val input = RecordingFile(browser)
            browser.acquire = { file ->
                assertSame(input, file)
                ManagedPlaybackTransportLease("https://example.test/video", PlaybackTransportType.DIRECT, emptyList())
            }
            val result = success(factory { error("No connection needed") }.acquire(browser, input, profile))
            result.lease.release()
            assertSame(profile, browser.receivedProfile)
            assertEquals(0, input.closes)
            assertEquals(0, browser.closes)
        }

    @Test
    fun cancellationDuringResolutionClosesTheCreatedConnection() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val browser = RecordingStorage(library(MediaType.SMB_SERVER))
            val playback =
                RecordingStorage(browser.library.copy()).apply {
                    resolve = {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            var returned = false
            val job =
                launch {
                    factory { playback }.acquire(browser, RecordingFile(browser), profile)
                    returned = true
                }
            entered.await()
            job.cancelAndJoin()
            assertFalse(returned)
            assertEquals(1, playback.closes)
            assertEquals(0, browser.closes)
        }

    @Test
    fun providerCancellationPropagatesAfterFileAndConnectionCleanup() =
        runBlocking {
            val browser = RecordingStorage(library(MediaType.FTP_SERVER))
            val playback =
                RecordingStorage(browser.library.copy()).apply {
                    acquire = { throw CancellationException("cancelled") }
                }
            val thrown =
                runCatching {
                    factory { playback }.acquire(browser, RecordingFile(browser), profile)
                }.exceptionOrNull()
            assertTrue(thrown is CancellationException)
            assertEquals(1, playback.closes)
            assertEquals(1, playback.resolvedFile!!.closes)
            assertEquals(0, browser.closes)
        }

    @Test
    fun cancellationBeforeDispatcherDeliversSuccessReleasesTheLeaseExactlyOnce() {
        val caller = QueuedDispatcher()
        val worker = QueuedDispatcher()
        val browser = RecordingStorage(library(MediaType.SMB_SERVER))
        val playback = RecordingStorage(browser.library.copy())
        val factory = PlaybackTransportFactory({ playback }, { it as? PlaybackTransportProvider }, worker)
        var delivered = false
        val job =
            CoroutineScope(Job() + caller).launch {
                factory.acquire(browser, RecordingFile(browser), profile)
                delivered = true
            }
        caller.runNext()
        worker.runNext()
        assertEquals(1, playback.providerCalls)
        job.cancel()
        while (caller.runNextIfPresent() || worker.runNextIfPresent()) {
            // Drain cancellation and cleanup continuations without a real thread race.
        }
        assertTrue(job.isCompleted)
        assertFalse(delivered)
        assertEquals(1, playback.closes)
        assertEquals(1, playback.resolvedFile!!.closes)
        assertEquals(0, browser.closes)
    }

    private fun factory(create: (MediaLibraryEntity) -> Storage?) =
        PlaybackTransportFactory(create, { it as? PlaybackTransportProvider }, Dispatchers.Unconfined)

    private fun success(result: TransportAcquisitionResult): AcquiredPlaybackTransport =
        (result as TransportAcquisitionResult.Success).transport

    private fun failure(result: TransportAcquisitionResult): TransportAcquisitionFailure =
        (result as TransportAcquisitionResult.Failure).reason

    private fun library(type: MediaType) =
        MediaLibraryEntity(
            id = 42,
            displayName = "Test library",
            url = "server.test",
            mediaType = type
        )

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable
        ) {
            queue.add(block)
        }

        fun runNext() {
            queue.removeFirst().run()
        }

        fun runNextIfPresent(): Boolean {
            val task = queue.pollFirst() ?: return false
            task.run()
            return true
        }
    }

    private class RecordingStorage(
        override var library: MediaLibraryEntity
    ) : Storage,
        PlaybackTransportProvider {
        override var directory: StorageFile? = null
        override var directoryFiles: List<StorageFile> = emptyList()
        override lateinit var rootUri: Uri
        var closes = 0
        var providerCalls = 0
        var receivedProfile: PlaybackProfile? = null
        val resolvedPaths = mutableListOf<String>()
        var resolvedFile: RecordingFile? = null
        var resolve: suspend (String) -> StorageFile? = { path ->
            RecordingFile(this, path).also { resolvedFile = it }
        }
        var acquire: suspend (StorageFile) -> PlaybackTransportLease = { file ->
            ManagedPlaybackTransportLease(
                "http://127.0.0.1:12000/video",
                PlaybackTransportType.SMB,
                listOf({ file.close() }, { close() })
            )
        }

        override suspend fun acquirePlaybackTransport(
            file: StorageFile,
            profile: PlaybackProfile
        ): PlaybackTransportLease {
            providerCalls++
            receivedProfile = profile
            return acquire(file)
        }

        override suspend fun pathFile(
            path: String,
            isDirectory: Boolean
        ): StorageFile? {
            assertFalse(isDirectory)
            resolvedPaths.add(path)
            return resolve(path)
        }

        override fun close() {
            closes++
        }

        override suspend fun getRootFile(): StorageFile? = null

        override suspend fun openFile(file: StorageFile): InputStream? = null

        override suspend fun openDirectory(
            file: StorageFile,
            refresh: Boolean
        ): List<StorageFile> = emptyList()

        override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? = null

        override suspend fun cacheDanmu(file: StorageFile): LocalDanmuBean? = null

        override suspend fun cacheSubtitle(file: StorageFile): String? = null

        override fun getNetworkHeaders(): Map<String, String>? = null

        override fun supportSearch(): Boolean = false

        override suspend fun search(keyword: String): List<StorageFile> = emptyList()

        override suspend fun test(): Boolean = true
    }

    private class RecordingFile(
        override var storage: Storage,
        private val path: String = "share/episode.mkv"
    ) : StorageFile {
        override var playHistory: PlayHistoryEntity? = null
        var closes = 0

        override fun storagePath(): String = path

        override fun uniqueKey(): String = "${storage.library.id}:$path"

        override fun filePath(): String = path.substringAfter('/')

        override fun fileUrl(): String = "test://$path"

        override fun fileCover(): String? = null

        override fun isDirectory(): Boolean = false

        override fun isFile(): Boolean = true

        override fun fileName(): String = path.substringAfterLast('/')

        override fun fileLength(): Long = 100

        override fun isRootFile(): Boolean = false

        override fun canRead(): Boolean = true

        override fun childFileCount(): Int = 0

        override fun <T> getFile(): T? = null

        override fun clone(): StorageFile = error("Cloning does not isolate the connection")

        override fun isVideoFile(): Boolean = true

        override fun isStoragePathParent(childPath: String): Boolean = false

        override fun close() {
            closes++
        }

        override fun videoDuration(): Long = 0
    }
}
