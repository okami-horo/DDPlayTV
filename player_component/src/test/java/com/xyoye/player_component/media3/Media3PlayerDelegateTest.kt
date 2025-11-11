package com.xyoye.player_component.media3

import com.xyoye.common_component.network.repository.Media3SessionBundle
import com.xyoye.data_component.entity.media3.Media3Capability
import com.xyoye.data_component.entity.media3.Media3PlayerEngine
import com.xyoye.data_component.entity.media3.Media3RolloutSource
import com.xyoye.data_component.entity.media3.Media3SourceType
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import com.xyoye.player_component.media3.session.Media3SessionController
import com.xyoye.player_component.media3.session.RolloutSnapshotManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Media3PlayerDelegateTest {

    @Test
    fun prepareSession_shortCircuits_whenToggleDisabled() = runTest {
        val controller = FakeSessionController()
        val toggleScript = ToggleScript(listOf(false))
        val delegate = Media3PlayerDelegate(
            sessionController = controller,
            snapshotManager = RolloutSnapshotManager { toggleScript.nextSnapshot() }
        )

        val result = delegate.prepareSession(
            mediaId = "anime-01",
            sourceType = Media3SourceType.STREAM
        )

        assertTrue(result.isFailure)
        assertEquals(0, controller.prepareInvocations)
        assertNull(delegate.currentSession())
    }

    @Test
    fun prepareSession_cachesBundle_whenControllerSucceeds() = runTest {
        val controller = FakeSessionController()
        val expected = sessionBundle("session-42")
        controller.prepareResult = Result.success(expected)
        val toggleScript = ToggleScript(listOf(true))
        val delegate = Media3PlayerDelegate(
            sessionController = controller,
            snapshotManager = RolloutSnapshotManager { toggleScript.nextSnapshot() }
        )

        val result = delegate.prepareSession(
            mediaId = "anime-42",
            sourceType = Media3SourceType.STREAM
        )

        assertTrue(result.isSuccess)
        assertEquals(expected.session.sessionId, delegate.currentSession()?.sessionId)
        assertEquals(expected.capabilityContract, delegate.currentCapability())
        val snapshot = delegate.rolloutSnapshot()
        assertEquals(expected.toggleSnapshot.value, snapshot?.value)
        assertEquals(expected.session.sessionId, snapshot?.appliesToSession)
    }

    @Test
    fun prepareSession_surfacesControllerErrors() = runTest {
        val controller = FakeSessionController()
        val failure = IllegalStateException("network down")
        controller.prepareResult = Result.failure(failure)
        val toggleScript = ToggleScript(listOf(true))
        val delegate = Media3PlayerDelegate(
            sessionController = controller,
            snapshotManager = RolloutSnapshotManager { toggleScript.nextSnapshot() }
        )

        val result = delegate.prepareSession(
            mediaId = "anime-17",
            sourceType = Media3SourceType.STREAM
        )

        assertTrue(result.isFailure)
        assertEquals(failure, result.exceptionOrNull())
        assertNull(delegate.currentSession())
    }

    private class FakeSessionController : Media3SessionController() {
        var prepareInvocations = 0
        var prepareResult: Result<Media3SessionBundle> = Result.success(sessionBundle())

        override suspend fun prepareSession(
            mediaId: String,
            sourceType: Media3SourceType,
            requestedCapabilities: List<Media3Capability>,
            autoplay: Boolean
        ): Result<Media3SessionBundle> {
            prepareInvocations++
            return prepareResult
        }
    }

    private class ToggleScript(values: List<Boolean>) {
        private val queue = ArrayDeque(values)
        private var counter = 0
        private var lastValue = false

        fun nextSnapshot(): RolloutToggleSnapshot {
            val value = if (queue.isEmpty()) lastValue else queue.removeFirst()
            lastValue = value
            counter += 1
            return RolloutToggleSnapshot(
                snapshotId = "snapshot-$counter",
                value = value,
                source = Media3RolloutSource.REMOTE_CONFIG,
                evaluatedAt = counter.toLong()
            )
        }
    }
}

private fun sessionBundle(
    sessionId: String = "session-1",
    mediaId: String = "anime-1"
): Media3SessionBundle {
    val capability = PlayerCapabilityContract(
        sessionId = sessionId,
        capabilities = listOf(Media3Capability.PLAY)
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
