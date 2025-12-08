package com.xyoye.common_component.network.repository

import com.xyoye.common_component.BuildConfig
import com.xyoye.common_component.media3.testing.Media3Dependent
import com.xyoye.common_component.network.service.Media3Service
import com.xyoye.data_component.data.media3.CapabilityCommandRequestData
import com.xyoye.data_component.data.media3.CapabilityCommandResponseData
import com.xyoye.data_component.data.media3.DownloadValidationRequestData
import com.xyoye.data_component.data.media3.DownloadValidationResponseData
import com.xyoye.data_component.data.media3.PlaybackSessionRequestData
import com.xyoye.data_component.data.media3.PlaybackSessionResponseData
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3PlayerEngine
import com.xyoye.data_component.entity.media3.Media3PlaybackState
import com.xyoye.data_component.entity.media3.Media3RolloutSource
import com.xyoye.data_component.entity.media3.Media3SourceType
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@Media3Dependent("Repository caches toggle snapshots using local build settings only")
class Media3RepositoryTest {

    private val fakeService = FakeMedia3Service()

    @Before
    fun setUp() {
        Media3Repository.replaceServiceForTest(fakeService)
        Media3Repository.clearCachesForTest()
    }

    @After
    fun tearDown() {
        Media3Repository.resetServiceForTest()
        Media3Repository.clearCachesForTest()
    }

    @Test
    fun createSession_cachesLocalToggleSnapshot() = runTest {
        fakeService.response = sessionResponse("session-create")

        Media3Repository.createSession(
            PlaybackSessionRequestData(
                mediaId = "media-create",
                sourceType = Media3SourceType.STREAM,
                autoplay = true,
                requestedCapabilities = listOf(Media3Capability.PLAY)
            )
        )

        val snapshot = Media3Repository.cachedToggle("session-create")
        assertNotNull(snapshot)
        assertEquals("session-create", snapshot?.appliesToSession)
        assertEquals(BuildConfig.MEDIA3_ENABLED_FALLBACK, snapshot?.value)
        assertEquals(Media3RolloutSource.GRADLE_FALLBACK, snapshot?.source)
    }

    @Test
    fun fetchSession_cachesLocalToggleSnapshot() = runTest {
        fakeService.response = sessionResponse("session-fetch")

        Media3Repository.fetchSession("session-fetch")

        val snapshot = Media3Repository.cachedToggle("session-fetch")
        assertNotNull(snapshot)
        assertEquals("session-fetch", snapshot?.appliesToSession)
        assertEquals(BuildConfig.MEDIA3_ENABLED_FALLBACK, snapshot?.value)
        assertEquals(Media3RolloutSource.GRADLE_FALLBACK, snapshot?.source)
    }

    private fun sessionResponse(sessionId: String) = PlaybackSessionResponseData(
        sessionId = sessionId,
        mediaId = "media-id",
        sourceType = Media3SourceType.STREAM,
        playbackState = Media3PlaybackState.READY,
        playerEngine = Media3PlayerEngine.MEDIA3,
        capabilityContract = PlayerCapabilityContract(
            sessionId = sessionId,
            capabilities = listOf(Media3Capability.PLAY)
        )
    )

    private class FakeMedia3Service : Media3Service {
        var response: PlaybackSessionResponseData? = null

        override suspend fun createSession(request: PlaybackSessionRequestData): PlaybackSessionResponseData {
            return response ?: throw IllegalStateException("response not set")
        }

        override suspend fun fetchSession(sessionId: String): PlaybackSessionResponseData {
            return response ?: throw IllegalStateException("response not set")
        }

        override suspend fun dispatchCommand(
            sessionId: String,
            request: CapabilityCommandRequestData
        ): CapabilityCommandResponseData {
            throw UnsupportedOperationException("Not needed for test")
        }

        override suspend fun emitTelemetry(event: com.xyoye.data_component.entity.media3.TelemetryEvent): Response<Unit> {
            throw UnsupportedOperationException("Not needed for test")
        }

        override suspend fun validateDownload(request: DownloadValidationRequestData): DownloadValidationResponseData {
            throw UnsupportedOperationException("Not needed for test")
        }
    }
}
