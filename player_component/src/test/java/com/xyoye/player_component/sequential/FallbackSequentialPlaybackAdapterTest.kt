package com.xyoye.player_component.sequential

import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackSequentialPlaybackAdapterTest {
    @Test
    fun queueNextSourceRemainsNoOpForLegacyBackends() {
        val adapter = FallbackSequentialPlaybackAdapter()
        val result =
            adapter.queueNextSource(
                sessionToken = SequentialPlaybackSessionToken(PlaybackSessionVersion("session", 1), "legacy", PlayerType.TYPE_VLC_PLAYER),
                queueItemId = SequentialPlaybackQueueItemId("1"),
                nextSource = FakeVideoSource()
            )

        assertTrue(!adapter.supportsSeamlessPlayback)
        assertTrue(result.isFailure)
    }

    private class FakeVideoSource : BaseVideoSource(0, listOf("legacy")) {
        override fun indexTitle(index: Int): String = "legacy"

        override fun getVideoUrl(): String = "file:///legacy.mp4"

        override fun getVideoTitle(): String = "legacy"

        override fun getCurrentPosition(): Long = 0L

        override fun getMediaType(): MediaType = MediaType.LOCAL_STORAGE

        override fun getUniqueKey(): String = "legacy"

        override fun getStorageId(): Int = 1
    }
}
