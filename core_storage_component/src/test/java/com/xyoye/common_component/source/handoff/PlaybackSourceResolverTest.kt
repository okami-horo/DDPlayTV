package com.xyoye.common_component.source.handoff

import com.tencent.mmkv.MMKV
import com.xunlei.downloadlib.parameter.BtTaskParam
import com.xyoye.common_component.network.request.NetworkException
import com.xyoye.common_component.playback.handoff.ExternalUriDescriptor
import com.xyoye.common_component.playback.handoff.PlayHistoryDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.common_component.playback.handoff.TorrentDescriptor
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.credential.CredentialUnavailableException
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.helper.SmbPlayServer
import com.xyoye.common_component.storage.file.helper.TorrentBean
import com.xyoye.common_component.storage.file.impl.RemoteStorageFile
import com.xyoye.common_component.storage.file.impl.SmbStorageFile
import com.xyoye.common_component.storage.impl.LinkStorage
import com.xyoye.common_component.storage.impl.RemoteStorage
import com.xyoye.common_component.storage.impl.ScreencastStorage
import com.xyoye.common_component.storage.impl.SmbConnection
import com.xyoye.common_component.storage.impl.SmbFileInfo
import com.xyoye.common_component.storage.impl.SmbStorage
import com.xyoye.common_component.storage.impl.TorrentStorage
import com.xyoye.common_component.storage.impl.WebDavStorage
import com.xyoye.common_component.storage.transport.PlaybackTransportFactory
import com.xyoye.common_component.utils.thunder.ThunderManager
import com.xyoye.common_component.utils.thunder.ThunderTaskOperations
import com.xyoye.common_component.utils.thunder.model.ThunderTorrentFileInfo
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.data.remote.RemoteVideoData
import com.xyoye.data_component.data.screeencast.ScreencastData
import com.xyoye.data_component.data.screeencast.ScreencastVideoData
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.StorageSort
import com.xyoye.data_component.media3.entity.DownloadRequiredAction
import com.xyoye.data_component.media3.entity.Media3SourceType
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, shadows = [PlaybackSourceResolverTest.DefaultPreferences::class])
class PlaybackSourceResolverTest {
    @get:Rule val temporary = TemporaryFolder()
    private val profile = PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.GLOBAL)
    private var library = MediaLibraryEntity(id = 19, displayName = "SMB", url = "fixture", mediaType = MediaType.SMB_SERVER)
    private var history: PlayHistoryEntity? = null
    private val backfills = mutableListOf<PlayHistoryEntity>()
    private val connections = mutableListOf<Connection>()
    private val sources = mutableListOf<StorageVideoSource>()
    private var load: suspend (Int) -> MediaLibraryEntity? = { library }
    private var create: (MediaLibraryEntity) -> Storage? = { smb(it) }
    private var validation: suspend (String, String, Long?) -> DownloadValidator.ValidationOutcome = { _, _, _ ->
        DownloadValidator.ValidationOutcome.AllowPlayback(false, null)
    }
    private var extras: suspend (StorageFile) -> StorageVideoSourceFactory.SourceExtras = {
        StorageVideoSourceFactory.SourceExtras(httpHeaders = it.storage.getNetworkHeaders(it))
    }
    private var afterAssembly: (StorageVideoSource) -> Unit = {}
    private var smbFailure: Exception? = null
    private val transportFactory = PlaybackTransportFactory({ create(it) }, ioDispatcher = Dispatchers.Unconfined)
    private val resolver get() =
        PlaybackSourceResolver(
            { load(it) },
            { history?.takeIf { row -> row.id == it } },
            { _, _ -> null },
            {
                backfills.add(it)
                history = it
            },
            { create(it) },
            { it },
            { a, b, c -> validation(a, b, c) },
            { file, _, cleanup ->
                StorageVideoSourceFactory
                    .createOwned(
                        file,
                        profile,
                        listOf(file),
                        acquire = { selected, selectedProfile -> transportFactory.acquire(selected.storage, selected, selectedProfile) },
                        prepareExtras = { extras(it) },
                        releaseResolution = cleanup
                    )?.also {
                        sources.add(it)
                        afterAssembly(it)
                    }
            },
            Dispatchers.Unconfined
        )

    @After fun cleanup() {
        sources.forEach { it.releasePlaybackResources() }
    }

    @Test fun capturesBoundedQueueWithSelectedItemAndFrozenSort() {
        val browser = smb(library)
        browser.directory = SmbStorageFile(browser, "videos", "", isDirectory = true)
        browser.directoryFiles = (1..250).map { SmbStorageFile(browser, "videos", "episode$it.mkv", it.toLong(), false) }
        val selected = browser.directoryFiles[230]
        val descriptor = PlaybackReferenceFactory.fromFile(selected, StorageSort.SIZE, true, false) as StoredLibraryFileDescriptor
        browser.directoryFiles = emptyList()
        browser.directory = null
        assertEquals(200, descriptor.queueContext!!.fallbackItems.size)
        assertTrue(descriptor.queueContext!!.fallbackItems.any { it.uniqueKey == selected.uniqueKey() })
        assertFalse(descriptor.queueContext!!.sortAscending)
        assertTrue(descriptor.queueContext!!.hideHiddenFiles)
        assertEquals("videos/", descriptor.queueContext!!.directory!!.storagePath)
        assertTrue(
            descriptor.queueContext!!
                .fallbackItems
                .first()
                .storagePath
                .endsWith("episode250.mkv")
        )
        browser.close()
    }

    @Test fun actualSmbResolverReopensStableQueueAfterPreviousLeaseCloses() =
        runBlocking {
            val browser = smb(library)
            browser.directory = browser.pathFile("videos/", true)
            browser.directoryFiles = browser.openDirectory(browser.directory!!, false)
            val descriptor = PlaybackReferenceFactory.fromFile(browser.directoryFiles.first(), StorageSort.NAME, true, false)
            val first = success(descriptor)
            browser.directoryFiles = emptyList()
            browser.close()
            assertEquals("episode1.mkv", read(first.source.getVideoUrl()))
            assertEquals(2, first.queue.size)
            val nextDescriptor = first.queue.first()
            first.source.releasePlaybackResources()
            val next = success(nextDescriptor)
            assertEquals("episode2.mkv", read(next.source.getVideoUrl()))
            assertEquals(listOf(1, 1, 1, 1, 0), connections.map { it.closes })
            next.source.releasePlaybackResources()
            assertTrue(connections.all { it.closes == 1 })
        }

    @Test fun failedCandidateAssemblyKeepsExistingProductionSmbLeaseAlive() =
        runBlocking {
            val descriptor = smbDescriptor()
            val active = success(descriptor)
            extras = { throw IOException("secret upstream address") }
            assertEquals(PlaybackPreparationFailure.NetworkUnavailable, failure(descriptor))
            assertEquals("episode1.mkv", read(active.source.getVideoUrl()))
            assertEquals(listOf(1, 0, 1, 1), connections.map { it.closes })
        }

    @Test fun cancellationDuringExtrasClosesCandidateButNotActiveLease() =
        runBlocking {
            val descriptor = smbDescriptor()
            val active = success(descriptor)
            val started = CompletableDeferred<Unit>()
            extras = {
                started.complete(Unit)
                awaitCancellation()
            }
            val job =
                launch {
                    resolver.resolve(descriptor)
                    fail("Cancelled preparation delivered")
                }
            started.await()
            job.cancelAndJoin()
            assertEquals("episode1.mkv", read(active.source.getVideoUrl()))
            assertEquals(listOf(1, 0, 1, 1), connections.map { it.closes })
        }

    @Test fun cancellationAtSourceDeliveryRetiresSourceAndQueryExactlyOnce() =
        runBlocking {
            lateinit var job: Job
            afterAssembly = { job.cancel() }
            job =
                launch(start = CoroutineStart.LAZY) {
                    resolver.resolve(smbDescriptor())
                    fail("Cancelled source delivered")
                }
            job.start()
            job.join()
            assertEquals(1, sources.size)
            assertEquals(listOf(1, 1), connections.map { it.closes })
            sources.single().releasePlaybackResources()
            assertEquals(listOf(1, 1), connections.map { it.closes })
        }

    @Test fun externalUriRestoresHeadersWithoutSharingInputMap() =
        runBlocking {
            create = { LinkStorage(it) }
            val headers = mutableMapOf("Authorization" to "test-secret")
            val descriptor = ExternalUriDescriptor("https://example.test/video.mkv", Media3SourceType.STREAM, "Episode", headers)
            val result = success(descriptor)
            headers.clear()
            assertEquals("test-secret", result.source.getHttpHeader()!!["Authorization"])
            assertEquals(descriptor.uri, result.source.getVideoUrl())
            assertEquals("Episode", result.identity.displayTitle)
        }

    @Test fun castCapturesSenderHeadersRelatedItemsAndRemoteProgress() =
        runBlocking {
            create = { ScreencastStorage(it, { _, _ -> null }, {}) }
            val videos = mutableListOf(ScreencastVideoData("first", "one", position = 1234), ScreencastVideoData("second", "two"))
            val headers = mutableMapOf("X-Token" to "cast-secret")
            val input = ScreencastData(1234, videos, "one", headers).apply { ip = "192.0.2.11" }
            val descriptor = PlaybackReferenceFactory.fromScreencast(input)
            input.ip = "192.0.2.12"
            videos.clear()
            headers.clear()
            val result = success(descriptor)
            assertTrue(result.source.getVideoUrl().contains("192.0.2.11:1234"))
            assertEquals("cast-secret", result.source.getHttpHeader()!!["X-Token"])
            assertEquals(1234L, result.source.getCurrentPosition())
            assertEquals(2, result.queue.size)
            assertEquals("two", success(result.queue[1]).identity.uniqueKey)
        }

    @Test fun historyBackfillsLegacyIdentityAndPathWithoutLosingProgress() =
        runBlocking {
            library = MediaLibraryEntity.STREAM.copy()
            create = { LinkStorage(it) }
            history =
                PlayHistoryEntity(
                    id = 4,
                    videoName = "legacy",
                    url = "https://example.test/video.mkv",
                    mediaType = MediaType.STREAM_LINK,
                    videoPosition = 5432,
                    httpHeader = "{\"X-Token\":\"history-secret\"}"
                )
            val result = success(PlayHistoryDescriptor(4, ""))
            assertEquals(5432L, result.source.getCurrentPosition())
            assertEquals("history-secret", result.source.getHttpHeader()!!["X-Token"])
            assertEquals(result.identity.uniqueKey, backfills.single().uniqueKey)
            assertEquals(library.id, backfills.single().storageId)
            assertEquals(PlaybackPreparationFailure.FileMissing, failure(PlayHistoryDescriptor(4, "replaced-row")))
            assertEquals(PlaybackPreparationFailure.FileMissing, failure(PlayHistoryDescriptor(5, "")))
        }

    @Test fun remoteIdentityIsReloadedByIdAndRejectsChangedHash() =
        runBlocking {
            library = MediaLibraryEntity(id = 17, displayName = "remote", url = "http://example.test", mediaType = MediaType.REMOTE_STORAGE)
            val original =
                RemoteVideoData(Id = "42", Hash = "hash", Path = "C:\\old\\video.mkv", Name = "video.mkv", absolutePath = "/old/video.mkv")
            val browser = RemoteStorage(library) { Result.success(listOf(original)) }
            val reference = PlaybackReferenceFactory.reference(RemoteStorageFile(browser, original))
            var current = original.copy(Path = "D:\\new\\video.mkv", absolutePath = "")
            create = { RemoteStorage(it) { Result.success(listOf(current)) } }
            val descriptor = StoredLibraryFileDescriptor(library.id, library.mediaType, reference)
            val result = success(descriptor)
            assertTrue(result.source.getVideoUrl().endsWith("/api/v1/stream/id/42"))
            assertEquals("42", reference.protocolReference)
            current = current.copy(Hash = "replacement")
            assertEquals(PlaybackPreparationFailure.FileMissing, failure(descriptor))
        }

    @Test fun torrentCompatibilityBlocksBeforeNativeAllocationAndAudioOnlyRemainsAdvisory() =
        runBlocking {
            val operations = TorrentOperations()
            val manager = ThunderManager(operations) { temporary.root }
            val torrent =
                TorrentBean("fixture.torrent", 1, "hash", false, "", listOf(ThunderTorrentFileInfo(0, "video.mkv", 5, "fixture.torrent")))
            var storageCreations = 0
            create = {
                storageCreations++
                TorrentStorage(it, manager, { torrent }, {})
            }
            val descriptor = TorrentDescriptor("fixture.torrent", 0, "download", "")
            for (action in listOf(DownloadRequiredAction.REVALIDATE, DownloadRequiredAction.REDOWNLOAD)) {
                validation = { _, _, _ -> DownloadValidator.ValidationOutcome.Blocked("blocked", action) }
                assertEquals(PlaybackPreparationFailure.OfflineValidationBlocked(action), failure(descriptor))
            }
            assertEquals(0, storageCreations)
            assertEquals(0, operations.created)
            for (audioOnly in listOf(false, true)) {
                validation = { _, _, _ -> DownloadValidator.ValidationOutcome.AllowPlayback(audioOnly, "advice") }
                val result = success(descriptor)
                assertEquals(audioOnly, result.downloadAdvice!!.audioOnly)
                assertEquals(0, result.source.getTorrentIndex())
                result.source.releasePlaybackResources()
            }
            assertEquals(2, operations.created)
            assertEquals(listOf(1L, 2L), operations.stopped)
        }

    @Test fun missingLibraryChangedIdentityAndTypedFailuresNeverAllocatePlayback() =
        runBlocking {
            val descriptor = smbDescriptor()
            load = { null }
            assertEquals(PlaybackPreparationFailure.LibraryMissing, failure(descriptor))
            load = { library }
            assertEquals(
                PlaybackPreparationFailure.FileMissing,
                failure(descriptor.copy(file = descriptor.file.copy(uniqueKey = "changed")))
            )
            val cases =
                listOf(
                    CredentialUnavailableException() to PlaybackPreparationFailure.CredentialUnavailable,
                    SecurityException("private path") to PlaybackPreparationFailure.PermissionDenied,
                    NetworkException(401, "private token", null) to PlaybackPreparationFailure.AuthenticationRequired,
                    IOException("private address") to PlaybackPreparationFailure.NetworkUnavailable
                )
            for ((exception, expected) in cases) {
                load = { throw exception }
                assertEquals(expected, failure(descriptor))
            }
            assertTrue(sources.isEmpty())
            assertTrue(connections.all { it.closes == 1 })
        }

    @Test fun wrappedCancellationIsPropagatedInsteadOfReportedAsNetworkFailure() =
        runBlocking {
            load = { throw NetworkException(2000, "cancelled", CancellationException()) }
            try {
                resolver.resolve(smbDescriptor())
                fail("Cancellation was swallowed")
            } catch (_: CancellationException) {
            }
        }

    @Test fun protocolFailureInFreshSmbLookupIsNotReportedAsMissingFile() =
        runBlocking {
            smbFailure = IOException("private endpoint")
            assertEquals(PlaybackPreparationFailure.NetworkUnavailable, failure(smbDescriptor()))
            assertEquals(listOf(1), connections.map { it.closes })
            assertTrue(sources.isEmpty())
        }

    @Test fun actualWebDavLookupReportsAuthenticationAndMissingFileSeparately() =
        runBlocking {
            var status = NanoHTTPD.Response.Status.UNAUTHORIZED
            val server =
                object : NanoHTTPD("127.0.0.1", 0) {
                    override fun serve(session: IHTTPSession): Response {
                        session.parseBody(mutableMapOf())
                        return newFixedLengthResponse(status, "text/plain", "fixture")
                    }
                }
            server.start()
            try {
                library =
                    MediaLibraryEntity(
                        id = 25,
                        displayName = "DAV",
                        url = "http://127.0.0.1:${server.listeningPort}",
                        mediaType = MediaType.WEBDAV_SERVER
                    )
                create = { WebDavStorage(it) }
                val descriptor = StoredLibraryFileDescriptor(library.id, library.mediaType, PlaybackFileReference("key", "/video.mkv"))
                assertEquals(PlaybackPreparationFailure.AuthenticationRequired, failure(descriptor))
                status = NanoHTTPD.Response.Status.NOT_FOUND
                assertEquals(PlaybackPreparationFailure.FileMissing, failure(descriptor))
                assertTrue(sources.isEmpty())
            } finally {
                server.stop()
            }
        }

    @Test fun missingDirectoryUsesCapturedFallbackOrderAndIgnoresBrowserChanges() =
        runBlocking {
            val browser = smb(library)
            browser.directoryFiles =
                listOf(
                    SmbStorageFile(browser, "videos", "episode1.mkv", 1, false),
                    SmbStorageFile(browser, "videos", "episode2.mkv", 2, false)
                )
            val descriptor = PlaybackReferenceFactory.fromFile(browser.directoryFiles[0], StorageSort.SIZE, true, false)
            browser.directoryFiles = emptyList()
            browser.close()
            val result = success(descriptor)
            assertEquals(
                listOf("videos/episode2.mkv", "videos/episode1.mkv"),
                result.queue.map {
                    (it as StoredLibraryFileDescriptor).file.storagePath
                }
            )
        }

    private suspend fun success(descriptor: PlaybackDescriptor): ResolvedPlayback {
        val result = resolver.resolve(descriptor, profile)
        assertTrue(
            "Expected success, got ${(result as? PlaybackResolutionResult.Failure)?.failure}",
            result is PlaybackResolutionResult.Success
        )
        return (result as PlaybackResolutionResult.Success).playback
    }

    private suspend fun failure(descriptor: PlaybackDescriptor): PlaybackPreparationFailure =
        (resolver.resolve(descriptor, profile) as PlaybackResolutionResult.Failure).failure

    private fun smbDescriptor(): StoredLibraryFileDescriptor {
        val browser = SmbStorage(library, Connection())
        val file = SmbStorageFile(browser, "videos", "episode1.mkv", 12, false)
        return StoredLibraryFileDescriptor(library.id, library.mediaType, PlaybackReferenceFactory.reference(file))
    }

    private fun smb(entity: MediaLibraryEntity): SmbStorage {
        val connection = Connection(smbFailure).also { connections.add(it) }
        return SmbStorage(entity, connection, { storage, file -> SmbPlayServer(storage, file, 0) })
    }

    private fun read(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private class Connection(
        private val statFailure: Exception? = null
    ) : SmbConnection {
        var closes = 0

        override fun shares(): List<String> = listOf("videos")

        override fun selectShare(name: String) {
            check(closes == 0)
        }

        override fun stat(path: String): SmbFileInfo {
            statFailure?.let { throw it }
            return SmbFileInfo(path.toByteArray().size.toLong(), path.isEmpty())
        }

        override fun list(path: String): List<String> = listOf("episode1.mkv", "episode2.mkv", ".hidden.mkv")

        override fun open(path: String): InputStream {
            check(closes == 0)
            return ByteArrayInputStream(path.toByteArray())
        }

        override fun close() {
            closes++
        }
    }

    private class TorrentOperations : ThunderTaskOperations {
        var created = 0
        val stopped = mutableListOf<Long>()

        override fun ensureReady(): Boolean = true

        override fun create(parameters: BtTaskParam): Long = (++created).toLong()

        override fun select(
            taskId: Long,
            selected: IntArray,
            deselected: IntArray
        ) = Unit

        override fun start(taskId: Long) = Unit

        override suspend fun awaitStarted(taskId: Long): Boolean = true

        override fun localUrl(path: String): String = "http://127.0.0.1/native/$created"

        override fun stop(taskId: Long) {
            stopped.add(taskId)
        }
    }

    /** Only default preferences are needed; native MMKV persistence has a separate device gate. */
    @Implements(value = MMKV::class, isInAndroidSdk = false)
    class DefaultPreferences {
        @Implementation fun decodeString(key: String): String? = null

        @Implementation fun decodeString(
            key: String,
            defaultValue: String?
        ): String? = defaultValue

        @Implementation fun decodeBool(
            key: String,
            defaultValue: Boolean
        ): Boolean = defaultValue

        @Implementation fun decodeInt(
            key: String,
            defaultValue: Int
        ): Int = defaultValue

        companion object {
            @JvmStatic @Implementation
            fun defaultMMKV(): MMKV = Shadow.newInstanceOf(MMKV::class.java)

            @JvmStatic @Implementation
            fun mmkvWithID(id: String): MMKV = Shadow.newInstanceOf(MMKV::class.java)
        }
    }
}
