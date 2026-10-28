package com.xyoye.common_component.source

import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.file.AbstractStorageFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.LinkStorage
import com.xyoye.common_component.storage.transport.AcquiredPlaybackTransport
import com.xyoye.common_component.storage.transport.ManagedPlaybackTransportLease
import com.xyoye.common_component.storage.transport.PlaybackTransportType
import com.xyoye.common_component.storage.transport.TransportAcquisitionResult
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StorageVideoSourceFactoryTest {
    private val profile = PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.GLOBAL)
    private val browser = storage()
    private val input = File(browser, "first")
    private val queue = listOf(input, File(browser, "second"))
    private val playbackFiles = mutableListOf<File>()
    private val headers = mutableMapOf("Authorization" to "first")
    private var extras: suspend (StorageFile) -> StorageVideoSourceFactory.SourceExtras = {
        assertSame(playbackFiles.last(), it)
        StorageVideoSourceFactory.SourceExtras(subtitlePath = "subtitle.ass", audioPath = "audio.flac", httpHeaders = headers)
    }

    private suspend fun create(): StorageVideoSource =
        StorageVideoSourceFactory.createOwned(
            input,
            profile,
            queue,
            acquire = { file, selected ->
                assertEquals(profile, selected)
                assertSame(browser, file.storage)
                assertEquals(0, (file as File).closes)
                val resolved = File(storage(), file.uniqueKey()).also { playbackFiles.add(it) }
                TransportAcquisitionResult.Success(
                    AcquiredPlaybackTransport(
                        resolved,
                        ManagedPlaybackTransportLease(resolved.fileUrl(), PlaybackTransportType.DIRECT, listOf({ resolved.close() }))
                    )
                )
            },
            prepareExtras = { extras(it) }
        )!!

    @Test
    fun sourceAndExtrasUseAcquiredFileAndSnapshotHeaders() =
        runBlocking {
            val source = create()
            assertSame(playbackFiles.single(), source.getStorageFile())
            assertNotSame(input, source.getStorageFile())
            assertEquals("subtitle.ass", source.getSubtitlePath())
            assertEquals("audio.flac", source.getAudioPath())
            headers["Authorization"] = "changed"
            assertEquals("first", source.getHttpHeader()!!["Authorization"])
            source.releasePlaybackResources()
            source.releasePlaybackResources()
            assertEquals(1, playbackFiles.single().closes)
            assertEquals(0, input.closes)
        }

    @Test
    fun sameIdentitySourcesRetireIndependently() =
        runBlocking {
            val first = create()
            val second = create()
            first.releasePlaybackResources()
            assertEquals(1, playbackFiles[0].closes)
            assertEquals(0, playbackFiles[1].closes)
            second.releasePlaybackResources()
        }

    @Test
    fun failedExtrasRetireAcquisition() =
        runBlocking {
            extras = { error("subtitle fixture failed") }
            assertFailure { create() }
            assertEquals(1, playbackFiles.single().closes)
        }

    @Test
    fun failedSourceConstructionRetiresLease() =
        runBlocking {
            extras = {
                (it as File).failIdentity = true
                StorageVideoSourceFactory.SourceExtras()
            }
            assertFailure { create() }
            assertEquals(1, playbackFiles.single().closes)
        }

    @Test
    fun cancellationDuringExtrasReleasesAcquiredOwner() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            extras = {
                started.complete(Unit)
                awaitCancellation()
            }
            val pending = launch { create() }
            started.await()
            pending.cancelAndJoin()
            assertEquals(1, playbackFiles.single().closes)
        }

    private suspend fun assertFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected construction failure")
        } catch (_: IllegalStateException) {
        }
    }

    private fun storage() =
        LinkStorage(MediaLibraryEntity(displayName = "fixture", url = "https://example.test", mediaType = MediaType.STREAM_LINK))

    private class File(
        storage: AbstractStorage,
        private val key: String
    ) : AbstractStorageFile(storage) {
        var closes = 0
        var failIdentity = false

        override fun uniqueKey(): String {
            check(!failIdentity && closes == 0) { "Playback file is unavailable" }
            return key
        }

        override fun getRealFile(): Any = key

        override fun filePath(): String = key

        override fun fileUrl(): String = "https://example.test/$key.mp4"

        override fun isDirectory(): Boolean = false

        override fun fileName(): String = "$key.mp4"

        override fun fileLength(): Long = 5

        override fun clone(): StorageFile = File(storage as AbstractStorage, key)

        override fun close() {
            closes++
        }
    }
}
