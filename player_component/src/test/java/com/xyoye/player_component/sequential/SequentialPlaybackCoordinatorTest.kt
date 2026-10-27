package com.xyoye.player_component.sequential

import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import com.xyoye.common_component.source.handoff.ResolvedPlayback
import com.xyoye.data_component.enums.MediaType
import com.xyoye.player_component.playback.session.PlaybackSessionTestFixture
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SequentialPlaybackCoordinatorTest {
    @Test fun stablePlaybackPreloadsOnceAndSessionAdoptsBeforeNextPreload() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            queue(f)
            f.session.deliver(f.submit("one"))
            val coordinator = SequentialPlaybackCoordinator(this, { f.session }, { true }, {}, { true })
            coordinator.onPlaybackStable()
            coordinator.onPlaybackProgress(100000, 1000, false)
            runCurrent()
            assertEquals(2, f.leases.size)
            assertEquals(3, f.backend.current!!.getGroupSize())
            val next = f.backend.queued!!
            f.backend.transition()
            f.session.nativeTransition(next.candidateId)
            assertEquals(1, f.backend.current!!.getGroupIndex())
            coordinator.onPlaybackProgress(100000, 1000, false)
            runCurrent()
            assertEquals(3, f.leases.size)
            assertEquals(1, f.leases.first().releases)
            f.session.exit()
        }

    @Test fun legacyBackendUsesControlledCompletionAndDisabledAutoNextDoesNothing() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            queue(f)
            f.session.deliver(f.submit("one"))
            var enabled = false
            val switches = mutableListOf<Int>()
            val coordinator = SequentialPlaybackCoordinator(this, { f.session }, { false }, switches::add, { enabled })
            coordinator.onPlaybackStable()
            coordinator.onPlaybackCompletionRequested(1)
            runCurrent()
            assertTrue(switches.isEmpty())
            enabled = true
            coordinator.onPlaybackCompletionRequested(1)
            runCurrent()
            assertEquals(listOf(1), switches)
            assertEquals(1, f.leases.size)
            assertNull(f.backend.queued)
            f.session.exit()
        }

    @Test fun bilibiliLongVideoOnlyPreloadsNearCompletionAndLiveNeverDoes() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            queue(f, MediaType.BILIBILI_STORAGE)
            f.session.deliver(f.submit("one"))
            val coordinator = SequentialPlaybackCoordinator(this, { f.session }, { true }, {}, { true })
            coordinator.onPlaybackStable()
            coordinator.onPlaybackProgress(1200000, 1000, false)
            runCurrent()
            assertNull(f.backend.queued)
            coordinator.onPlaybackProgress(1200000, 1199999, true)
            runCurrent()
            assertNull(f.backend.queued)
            coordinator.onPlaybackProgress(1200000, 1199999, false)
            runCurrent()
            assertNotNull(f.backend.queued)
            f.session.exit()
        }

    @Test fun endOfQueueAndUnstablePlaybackNeverPreload() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            queue(f)
            f.session.deliver(f.submit("three"))
            val switches = mutableListOf<Int>()
            val coordinator = SequentialPlaybackCoordinator(this, { f.session }, { true }, switches::add, { true })
            coordinator.onPlaybackStable()
            coordinator.onPlaybackCompletionRequested(3)
            runCurrent()
            assertEquals(1, f.leases.size)
            assertTrue(switches.isEmpty())
            f.session.selectQueueItem(0, f.session.runtime!!.version)
            coordinator.invalidateActiveSession()
            coordinator.onPlaybackProgress(100000, 1000, false)
            runCurrent()
            assertNull(f.backend.queued)
            f.session.exit()
        }

    private fun queue(
        f: PlaybackSessionTestFixture,
        type: MediaType = MediaType.STREAM_LINK
    ) {
        val descriptors = listOf("one", "two", "three").map(f::descriptor)
        f.resolve = {
            val resolved = f.resolved(it)
            PlaybackResolutionResult.Success(
                ResolvedPlayback(resolved.source, resolved.descriptor, resolved.identity.copy(mediaType = type), descriptors)
            )
        }
    }
}
