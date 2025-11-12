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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@Media3Dependent("Ensures toggle snapshots persist per Media3 session")
@RunWith(AndroidJUnit4::class)
class Media3ToggleSnapshotTest {

    @Test
    fun activeSessionPersistsWhenToggleFlips() = runBlocking {
        val controller = FakeSessionController()
        val toggleScript = ToggleScript(listOf(true, false))
        val delegate = Media3PlayerDelegate(
            sessionController = controller,
            snapshotManager = RolloutSnapshotManager { toggleScript.nextSnapshot() },
            telemetrySink = NoOpTelemetrySink()
        )

        val firstResult = delegate.prepareSession("media-a", Media3SourceType.STREAM)
        assertTrue(firstResult.isSuccess)
        val activeSessionId = delegate.currentSession()?.sessionId

        val secondResult = delegate.prepareSession("media-b", Media3SourceType.STREAM)
        assertTrue(secondResult.isFailure)

        assertEquals(activeSessionId, delegate.currentSession()?.sessionId)
        assertTrue(delegate.rolloutSnapshot()?.value == true)
        assertTrue(toggleScript.wasExhausted()) // ensures flip path executed
    }

    private class FakeSessionController : Media3SessionController() {
        override suspend fun prepareSession(
            mediaId: String,
            sourceType: Media3SourceType,
            requestedCapabilities: List<Media3Capability>,
            autoplay: Boolean
        ): Result<Media3SessionBundle> {
            return Result.success(buildBundle("session-$mediaId", mediaId))
        }
    }
}

private class ToggleScript(values: List<Boolean>) {
    private val queue = ArrayDeque(values)
    private var lastValue = false
    private var exhausted = false

    fun nextSnapshot(): RolloutToggleSnapshot {
        val value = if (queue.isEmpty()) {
            exhausted = true
            lastValue
        } else {
            val next = queue.removeFirst()
            if (queue.isEmpty()) {
                exhausted = true
            }
            lastValue = next
            next
        }
        return RolloutToggleSnapshot(
            snapshotId = "snapshot-${System.nanoTime()}",
            value = value,
            source = Media3RolloutSource.REMOTE_CONFIG,
            evaluatedAt = System.currentTimeMillis()
        )
    }

    fun wasExhausted(): Boolean = exhausted
}

private fun buildBundle(
    sessionId: String,
    mediaId: String
): Media3SessionBundle {
    val capability = PlayerCapabilityContract(
        sessionId = sessionId,
        capabilities = listOf(Media3Capability.PLAY, Media3Capability.PAUSE)
    )
    val snapshot = RolloutToggleSnapshot(
        snapshotId = "snapshot-$sessionId",
        value = true,
        source = Media3RolloutSource.REMOTE_CONFIG,
        evaluatedAt = 0L,
        appliesToSession = sessionId
    )
    val session = PlaybackSession(
        sessionId = sessionId,
        mediaId = mediaId,
        sourceType = Media3SourceType.STREAM,
        playerEngine = Media3PlayerEngine.MEDIA3
    )
    return Media3SessionBundle(session, capability, snapshot)
}
