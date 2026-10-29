package com.xyoye.player_component.playback.session

import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackInternalRecoveryTest {
    @Test fun kernelFallbackReusesLeaseAndRevision() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.session.deliver(f.submit("same"))
            val before = f.session.runtime!!
            val snapshot = f.blob.data
            before.playback.source.setSubtitlePath("retained.ass")
            f.backend.playbackProgress = PlaybackProgress(900, false)
            val fallback = PlaybackProfile(PlayerType.TYPE_MPV_PLAYER, PlaybackProfileSource.FALLBACK)
            assertTrue(f.session.fallbackKernel(before.version, fallback) is SessionRequestResult.Activated)
            assertSame(before.playback.source, f.backend.current)
            assertEquals(before.version, f.session.runtime!!.version)
            assertEquals(
                fallback,
                f.session.runtime!!
                    .playback.runtimeProfile
            )
            assertEquals(snapshot, f.blob.data)
            assertEquals(900L, f.backend.progress().positionMs)
            assertEquals("retained.ass", f.backend.current!!.getSubtitlePath())
            assertEquals(1, f.leases.size)
            assertEquals(0, f.leases.single().releases)
            f.session.deliver(f.submit("new"))
            assertTrue(f.session.fallbackKernel(before.version, fallback) is SessionRequestResult.Rejected)
            assertEquals("new", f.backend.current!!.getVideoTitle())
            f.session.exit()
        }
}
