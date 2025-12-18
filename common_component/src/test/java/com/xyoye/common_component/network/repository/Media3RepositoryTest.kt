package com.xyoye.common_component.network.repository

import com.xyoye.common_component.media3.testing.Media3Dependent
import com.xyoye.data_component.data.media3.DownloadValidationRequestData
import com.xyoye.data_component.data.media3.PlaybackSessionRequestData
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3SourceType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@Media3Dependent("Media3 gateway is disabled; validate local fallbacks")
class Media3RepositoryTest {
    @Before
    fun setUp() {
        Media3Repository.clearCachesForTest()
    }

    @After
    fun tearDown() {
        Media3Repository.clearCachesForTest()
    }

    @Test
    fun createSession_returnsFailureWhenGatewayDisabled() =
        runTest {
            val result =
                Media3Repository.createSession(
                    PlaybackSessionRequestData(
                        mediaId = "media-create",
                        sourceType = Media3SourceType.STREAM,
                        autoplay = true,
                        requestedCapabilities = listOf(Media3Capability.PLAY),
                    ),
                )
            assertNotNull(result.exceptionOrNull())
        }

    @Test
    fun validateDownload_returnsCompatibleWhenGatewayDisabled() =
        runTest {
            val result =
                Media3Repository.validateDownload(
                    DownloadValidationRequestData(
                        downloadId = "download-1",
                        mediaId = "media-1",
                        media3Version = "1.8.0-test",
                        lastVerifiedAt = null,
                    ),
                )
            val response = result.getOrThrow()
            assertTrue(response.isCompatible)
            assertNotNull(response.downloadId)
        }
}
