package com.xyoye.common_component.network.repository

import com.xyoye.common_component.media3.testing.Media3Dependent
import com.xyoye.data_component.media3.dto.DownloadValidationRequestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Media3Dependent("Media3 gateway is disabled; validate local fallbacks")
@OptIn(ExperimentalCoroutinesApi::class)
class Media3RepositoryTest {
    @Test
    fun validateDownload_returnsCompatibleWhenGatewayDisabled() =
        runTest {
            val result =
                Media3Repository.validateDownload(
                    DownloadValidationRequestData(
                        downloadId = "download-1",
                        mediaId = "media-1",
                        media3Version = "1.8.0-test",
                        lastVerifiedAt = null
                    )
                )
            val response = result.getOrThrow()
            assertTrue(response.isCompatible)
            assertNotNull(response.downloadId)
        }
}
