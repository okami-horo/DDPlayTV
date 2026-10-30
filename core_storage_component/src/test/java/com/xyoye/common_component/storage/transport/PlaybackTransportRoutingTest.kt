package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.source.handoff.PlaybackSourceResolverTest.DefaultPreferences
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.file.AbstractStorageFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.AlistStorageFile
import com.xyoye.common_component.storage.file.impl.RemoteStorageFile
import com.xyoye.common_component.storage.impl.*
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.data.alist.AlistFileData
import com.xyoye.data_component.data.remote.RemoteVideoData
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, shadows = [DefaultPreferences::class])
class PlaybackTransportRoutingTest {
    private val profile = PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.GLOBAL)

    @Test
    fun knownDirectLinkUsesTheDefaultFactoryAndBorrowsMetadata() =
        runBlocking {
            val storage =
                LinkStorage(
                    MediaLibraryEntity(displayName = "Link", url = "https://example.test/video.mp4", mediaType = MediaType.STREAM_LINK)
                )
            val file = storage.getRootFile()
            val result = PlaybackTransportFactory().acquire(storage, file, profile)
            assertTrue("Known direct source must have an explicit provider", result is TransportAcquisitionResult.Success)
            val transport = (result as TransportAcquisitionResult.Success).transport
            assertSame(file, transport.playbackFile)
            assertEquals(PlaybackTransportType.DIRECT, transport.lease.transportType)
            assertEquals(file.fileUrl(), transport.lease.playUrl)
            transport.lease.release()
            assertEquals(file.fileUrl(), storage.getRootFile().fileUrl())
        }

    @Test
    fun localDocumentAndCastProvidersBorrowFilesWithoutClosingThem() =
        runBlocking {
            val cases =
                listOf(
                    VideoStorage(library(MediaType.LOCAL_STORAGE)) to "/media/video.mp4",
                    DocumentFileStorage(library(MediaType.EXTERNAL_STORAGE)) to "content://media/external/video/1",
                    ScreencastStorage(library(MediaType.SCREEN_CAST)) to "https://example.test/cast"
                )
            for ((storage, url) in cases) {
                val file = MetadataFile(storage, url)
                val result = PlaybackTransportFactory().acquire(storage, file, profile) as TransportAcquisitionResult.Success
                assertSame(file, result.transport.playbackFile)
                assertEquals(url, result.transport.lease.playUrl)
                result.transport.lease.release()
                assertEquals(0, file.closes)
            }
        }

    @Test
    fun webDavDefaultFactoryPreservesDirectUrlAndAuthorization() =
        runBlocking {
            val storage = WebDavStorage(library(MediaType.WEBDAV_SERVER).copy(account = "viewer", password = "fixture"))
            val file = storage.pathFile("/video.mp4", false)
            val result = PlaybackTransportFactory().acquire(storage, file, profile) as TransportAcquisitionResult.Success
            assertEquals(file.fileUrl(), result.transport.lease.playUrl)
            assertEquals(PlaybackTransportType.DIRECT, result.transport.lease.transportType)
            val headers = storage.getNetworkHeaders()
            result.transport.lease.release()
            assertEquals(headers, storage.getNetworkHeaders())
            assertTrue(headers!!.values.single().startsWith("Basic "))
        }

    @Test
    fun remoteDefaultFactoryRetainsPerLibraryToken() =
        runBlocking {
            val first = RemoteStorage(library(MediaType.REMOTE_STORAGE).copy(remoteSecret = "first"))
            val second = RemoteStorage(library(MediaType.REMOTE_STORAGE).copy(remoteSecret = "second"))
            val one = RemoteStorageFile(first, RemoteVideoData(Id = "1", absolutePath = "/video.mp4"))
            val two = RemoteStorageFile(second, RemoteVideoData(Id = "1", absolutePath = "/video.mp4"))
            val a = (PlaybackTransportFactory().acquire(first, one, profile) as TransportAcquisitionResult.Success).transport.lease
            val b = (PlaybackTransportFactory().acquire(second, two, profile) as TransportAcquisitionResult.Success).transport.lease
            assertNotEquals(a.playUrl, b.playUrl)
            assertTrue(a.playUrl.endsWith("token=first"))
            a.release()
            assertTrue(b.playUrl.endsWith("token=second"))
            b.release()
        }

    @Test
    fun alistDefaultFactoryUsesExistingCachedUpstreamWhenRefreshHasNoCredentials() =
        runBlocking {
            val storage = AlistStorage(library(MediaType.ALSIT_STORAGE))
            val file = AlistStorageFile("/", AlistFileData("video.mp4", rawUrl = "https://example.test/raw"), storage)
            val result = PlaybackTransportFactory().acquire(storage, file, profile) as TransportAcquisitionResult.Success
            assertEquals("https://example.test/raw", result.transport.lease.playUrl)
            result.transport.lease.release()
        }

    @Test
    fun cloudProvidersRejectNonVideoInsteadOfFallingBackToItsRawUrl() =
        runBlocking {
            val storages =
                listOf(
                    BaiduPanStorage(library(MediaType.BAIDU_PAN_STORAGE)),
                    Open115Storage(library(MediaType.OPEN_115_STORAGE)),
                    Cloud115Storage(library(MediaType.CLOUD_115_STORAGE))
                )
            for (storage in storages) {
                val result =
                    PlaybackTransportFactory().acquire(
                        storage,
                        MetadataFile(storage, "https://example.test/raw", video = false),
                        profile
                    )
                assertTrue(result is TransportAcquisitionResult.Failure)
                val failure = result as TransportAcquisitionResult.Failure
                assertNotEquals(TransportAcquisitionFailure.UnsupportedTransport, failure.reason)
                assertTrue(failure.cause is IllegalStateException)
                assertTrue(failure.cause!!.message!!.contains("不是视频"))
            }
        }

    private fun library(type: MediaType) = MediaLibraryEntity(displayName = "fixture", url = "https://example.test", mediaType = type)

    private class MetadataFile(
        storage: AbstractStorage,
        private val url: String,
        private val video: Boolean = true
    ) : AbstractStorageFile(storage) {
        var closes = 0

        override fun getRealFile(): Any = Unit

        override fun filePath(): String = url

        override fun fileUrl(): String = url

        override fun isDirectory(): Boolean = false

        override fun fileName(): String = "video.mp4"

        override fun fileLength(): Long = 5

        override fun isVideoFile(): Boolean = video

        override fun clone(): StorageFile = MetadataFile(storage as AbstractStorage, url, video)

        override fun close() {
            closes++
        }
    }
}
