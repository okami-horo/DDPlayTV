package com.xyoye.player_component.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xyoye.common_component.media3.testing.Media3Dependent
import com.xyoye.common_component.network.repository.Media3SessionBundle
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3PlayerEngine
import com.xyoye.data_component.entity.media3.Media3RolloutSource
import com.xyoye.data_component.entity.media3.Media3SourceType
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import com.xyoye.player_component.media3.Media3PlayerDelegate
import com.xyoye.player_component.media3.session.Media3SessionController
import com.xyoye.player_component.media3.session.RolloutSnapshotManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@Media3Dependent("Smoke test validates Media3 startup latency")
@RunWith(AndroidJUnit4::class)
class Media3PlaybackSmokeTest {
    @Test
    fun firstFrameWithinTwoSeconds_passesSmokeBudget() =
        runBlocking {
            val controller = FakeSessionController()
            val clock = FakeClock()
            val delegate =
                Media3PlayerDelegate(
                    sessionController = controller,
                    snapshotManager = RolloutSnapshotManager { snapshot(value = true, now = clock.now()) },
                    telemetrySink = NoOpTelemetrySink(),
                    timeProvider = clock::now,
                )

            val result = delegate.prepareSession("media-smoke", Media3SourceType.STREAM)
            assertTrue(result.isSuccess)

            clock.advance(1_500)
            delegate.markFirstFrame()

            assertEquals(1_500, delegate.startupLatencyMs())
            assertTrue(delegate.isStartupWithinTarget(Media3PlayerDelegate.STARTUP_BUDGET_MS))
        }

    private class FakeSessionController : Media3SessionController() {
        override suspend fun prepareSession(
            mediaId: String,
            sourceType: Media3SourceType,
            requestedCapabilities: List<Media3Capability>,
            autoplay: Boolean
        ): Result<Media3SessionBundle> = Result.success(sessionBundle(sessionId = "session-$mediaId", mediaId = mediaId))
    }

    private class FakeClock {
        private var nowMs: Long = 0L

        fun advance(delta: Long) {
            nowMs += delta
        }

        fun now(): Long = nowMs
    }
}

private fun sessionBundle(
    sessionId: String,
    mediaId: String
): Media3SessionBundle {
    val capability =
        PlayerCapabilityContract(
            sessionId = sessionId,
            capabilities = listOf(Media3Capability.PLAY),
        )
    val snapshot =
        RolloutToggleSnapshot(
            snapshotId = "snapshot-$sessionId",
            value = true,
            source = Media3RolloutSource.REMOTE_CONFIG,
            evaluatedAt = 0L,
            appliesToSession = sessionId,
        )
    val session =
        PlaybackSession(
            sessionId = sessionId,
            mediaId = mediaId,
            sourceType = Media3SourceType.STREAM,
            playerEngine = Media3PlayerEngine.MEDIA3,
        )
    return Media3SessionBundle(session, capability, snapshot)
}

private fun snapshot(
    value: Boolean,
    now: Long
): RolloutToggleSnapshot =
    RolloutToggleSnapshot(
        snapshotId = "snapshot-$now",
        value = value,
        source = Media3RolloutSource.REMOTE_CONFIG,
        evaluatedAt = now,
    )
