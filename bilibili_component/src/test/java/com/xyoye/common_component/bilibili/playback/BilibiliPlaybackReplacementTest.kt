package com.xyoye.common_component.bilibili.playback

import com.xyoye.common_component.bilibili.repository.BilibiliRepository
import com.xyoye.common_component.playback.addon.PlaybackIdentity
import com.xyoye.common_component.playback.addon.PlaybackRecoveryRequest
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BilibiliPlaybackReplacementTest {
    @After fun cleanup() = BilibiliLivePlaybackSessionStore.clear()

    @Test fun requestingRecoveryMustNotMutateTheActiveLiveTransport() =
        runTest {
            val key = "bilibili://live/1"
            val active = BilibiliLivePlaybackSession(7, key, "fixture", BilibiliRepository("fixture"), 1)
            field("urlCandidates").set(active, listOf("https://first.test/live", "https://second.test/live"))
            field("selectedIndex").setInt(active, 0)
            val token = BilibiliLivePlaybackSessionStore.put(active)
            val identity = PlaybackIdentity(7, key, MediaType.BILIBILI_STORAGE, key, "Live", "https://first.test/live")
            val addon = BilibiliPlaybackAddon(identity, token)
            val decision = addon.recover(PlaybackRecoveryRequest(identity, 0, null, mapOf("failingUrl" to identity.videoUrl)))
            assertTrue(decision.isSuccess)
            assertEquals("Recovery preparation changed the active CDN before a session commit", 0, field("selectedIndex").getInt(active))
            assertSame(active, BilibiliLivePlaybackSessionStore.get(7, key, token))
        }

    private fun field(name: String) = BilibiliLivePlaybackSession::class.java.getDeclaredField(name).apply { isAccessible = true }
}
