package com.xyoye.common_component.source.handoff

import com.tencent.mmkv.MMKV
import com.xyoye.common_component.network.request.NetworkException
import com.xyoye.common_component.playback.handoff.PlayHistoryDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.credential.CredentialUnavailableException
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.helper.SmbPlayServer
import com.xyoye.common_component.storage.file.impl.SmbStorageFile
import com.xyoye.common_component.storage.impl.ScreencastStorage
import com.xyoye.common_component.storage.impl.SmbConnection
import com.xyoye.common_component.storage.impl.SmbFileInfo
import com.xyoye.common_component.storage.impl.SmbStorage
import com.xyoye.common_component.storage.impl.WebDavStorage
import com.xyoye.common_component.storage.transport.PlaybackTransportFactory
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.data.screeencast.ScreencastData
import com.xyoye.data_component.data.screeencast.ScreencastVideoData
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.StorageSort
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
    private var extras: suspend (StorageFile) -> StorageVideoSourceFactory.SourceExtras = {
        StorageVideoSourceFactory.SourceExtras(httpHeaders = it.storage.getNetworkHeaders(it))
    }
    private var afterAssembly: (StorageVideoSource) -> Unit = {}
    private var smbFailure: Exception? = null
    private var catalogSource: suspend (Int) -> com.xyoye.data_component.entity.CatalogSourceState? = { null }
    private val transportFactory = PlaybackTransportFactory({ create(it) }, ioDispatcher = Dispatchers.Unconfined)
    private val resolver get() =
        PlaybackSourceResolver(
            { load(it) },
            { history?.takeIf { row -> row.id == it } },
            { key, id -> history?.takeIf { it.uniqueKey == key && it.storageId == id } },
            {
                backfills.add(it)
                history = it
            },
            { create(it) },
            { it },
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
            Dispatchers.Unconfined,
            catalogSource = { catalogSource(it) }
        )

    @After fun cleanup() {
        sources.forEach { it.releasePlaybackResources() }
        DefaultPreferences.playerType = PlayerType.TYPE_EXO_PLAYER.value
    }

    @Test fun newPlaybackProfilesReadTheGlobalKernelAndNormalizeRetiredValues() {
        val expectedTypes =
            mapOf(
                2 to PlayerType.TYPE_EXO_PLAYER,
                4 to PlayerType.TYPE_MPV_PLAYER,
                3 to PlayerType.TYPE_EXO_PLAYER,
                -1 to PlayerType.TYPE_EXO_PLAYER
            )
        for ((value, expected) in expectedTypes) {
            DefaultPreferences.playerType = value
            val resolved = StorageVideoSourceFactory.resolvePlaybackProfile()
            assertEquals(expected, resolved.playerType)
            assertEquals(PlaybackProfileSource.GLOBAL, resolved.source)
        }
        DefaultPreferences.playerType = 4
        val prepared = StorageVideoSourceFactory.resolvePlaybackProfile()
        DefaultPreferences.playerType = 2
        assertEquals(PlayerType.TYPE_MPV_PLAYER, prepared.playerType)
        assertEquals(PlayerType.TYPE_EXO_PLAYER, StorageVideoSourceFactory.resolvePlaybackProfile().playerType)
    }

    @Test fun playbackCapturesCatalogVersionAndDropsMetadataWhenConfigurationChangesDuringResolution() =
        runBlocking {
            val source =
                com.xyoye.data_component.entity
                    .CatalogSourceState(library.id, 3, 7)
            catalogSource = { source }
            val resolved = success(smbDescriptor())
            assertEquals(source, resolved.metadataFile?.source)
            assertEquals("videos", resolved.metadataFile?.parentPath)
            var reads = 0
            catalogSource = { if (reads++ == 0) source else source.copy(revision = 8) }
            val changed = success(smbDescriptor())
            assertNull(changed.metadataFile)
            assertEquals("episode1.mkv", read(changed.source.getVideoUrl()))
        }

    @Test fun retiredPcDescriptorsAndHistoryCannotOpenAnyConnection() =
        runBlocking {
            library = library.copy(mediaType = MediaType.REMOTE_STORAGE)
            create = { error("Retired source must not create a connection") }
            val descriptor = StoredLibraryFileDescriptor(library.id, library.mediaType, PlaybackFileReference("legacy", "/old.mkv"))
            assertEquals(PlaybackPreparationFailure.UnsupportedSource, failure(descriptor))
            history =
                PlayHistoryEntity(
                    id = 5,
                    videoName = "old",
                    url = "http://old.test/video",
                    mediaType = library.mediaType,
                    uniqueKey = "legacy",
                    storageId = library.id,
                    videoPosition = 5432
                )
            assertEquals(PlaybackPreparationFailure.UnsupportedSource, failure(PlayHistoryDescriptor(5, "legacy", library.id)))
            assertEquals(5432L, history!!.videoPosition)
            assertTrue(backfills.isEmpty())
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
            history =
                PlayHistoryEntity(
                    id = 4,
                    videoName = "legacy",
                    url = "smb://fixture/videos/episode1.mkv",
                    mediaType = library.mediaType,
                    storageId = library.id,
                    videoPosition = 5432,
                    danmuPath = "saved.xml",
                    subtitlePath = "saved.ass"
                )
            val result = success(PlayHistoryDescriptor(4, ""))
            assertEquals(5432L, result.source.getCurrentPosition())
            assertEquals(result.identity.uniqueKey, backfills.single().uniqueKey)
            assertEquals("videos/episode1.mkv", backfills.single().storagePath)
            assertEquals("saved.xml", backfills.single().danmuPath)
            assertEquals("saved.ass", backfills.single().subtitlePath)
            assertEquals(library.id, backfills.single().storageId)
            assertEquals(PlaybackPreparationFailure.FileMissing, failure(PlayHistoryDescriptor(4, "replaced-row")))
            assertEquals(PlaybackPreparationFailure.FileMissing, failure(PlayHistoryDescriptor(5, "")))
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

    /** Native MMKV persistence has a separate device gate. */
    @Implements(value = MMKV::class, isInAndroidSdk = false)
    class DefaultPreferences {
        @Implementation fun getBoolean(
            key: String,
            defaultValue: Boolean
        ): Boolean = defaultValue

        @Implementation fun getInt(
            key: String,
            defaultValue: Int
        ): Int = if (key == "key_use_player_type") playerType else defaultValue

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
        ): Int = if (key == "key_use_player_type") playerType else defaultValue

        companion object {
            var playerType = PlayerType.TYPE_EXO_PLAYER.value

            @JvmStatic @Implementation
            fun defaultMMKV(): MMKV = Shadow.newInstanceOf(MMKV::class.java)

            @JvmStatic @Implementation
            fun mmkvWithID(id: String): MMKV = Shadow.newInstanceOf(MMKV::class.java)
        }
    }
}
