package com.xyoye.common_component.network.repository

import com.xyoye.common_component.network.service.Media3Service
import com.xyoye.data_component.data.media3.CapabilityCommandRequestData
import com.xyoye.data_component.data.media3.CapabilityCommandResponseData
import com.xyoye.data_component.data.media3.DownloadValidationRequestData
import com.xyoye.data_component.data.media3.DownloadValidationResponseData
import com.xyoye.data_component.data.media3.PlaybackSessionRequestData
import com.xyoye.data_component.data.media3.PlaybackSessionResponseData
import com.xyoye.data_component.data.media3.RolloutTogglePatchData
import com.xyoye.data_component.entity.media3.Media3RolloutSource
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

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
    fun updateRollout_cachesSnapshotWhenResponseContainsSession() = runTest {
        val sessionId = "session-100"
        val snapshot = snapshot(appliesToSession = sessionId)
        fakeService.snapshotToReturn = snapshot

        val patch = RolloutTogglePatchData(
            value = true,
            source = Media3RolloutSource.MANUAL_OVERRIDE,
            reason = "canary"
        )

        Media3Repository.updateRollout(patch)

        assertEquals(snapshot, Media3Repository.cachedToggle(sessionId))
    }

    @Test
    fun updateRollout_skipsCacheWhenSessionMissing() = runTest {
        fakeService.snapshotToReturn = snapshot(appliesToSession = null)

        val patch = RolloutTogglePatchData(
            value = false,
            source = Media3RolloutSource.REMOTE_CONFIG,
            reason = "rollback"
        )

        Media3Repository.updateRollout(patch)

        assertNull(Media3Repository.cachedToggle("any-session"))
    }

    private fun snapshot(appliesToSession: String?) = RolloutToggleSnapshot(
        snapshotId = "snapshot-${appliesToSession ?: "none"}",
        value = appliesToSession != null,
        source = Media3RolloutSource.REMOTE_CONFIG,
        evaluatedAt = 1_000L,
        appliesToSession = appliesToSession
    )

    private class FakeMedia3Service : Media3Service {
        var snapshotToReturn: RolloutToggleSnapshot? = null

        override suspend fun createSession(request: PlaybackSessionRequestData): PlaybackSessionResponseData {
            throw UnsupportedOperationException("Not needed for test")
        }

        override suspend fun fetchSession(sessionId: String): PlaybackSessionResponseData {
            throw UnsupportedOperationException("Not needed for test")
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

        override suspend fun updateRollout(patch: RolloutTogglePatchData): RolloutToggleSnapshot {
            return snapshotToReturn ?: RolloutToggleSnapshot(
                snapshotId = "default",
                value = patch.value,
                source = patch.source,
                evaluatedAt = 0L,
                appliesToSession = null
            )
        }

        override suspend fun validateDownload(request: DownloadValidationRequestData): DownloadValidationResponseData {
            throw UnsupportedOperationException("Not needed for test")
        }
    }
}
