package com.xyoye.player_component.sequential

import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3SequentialPlaybackAdapterTest {
    @Test
    fun queueNextSourceTrimsQueueAndEnqueuesTaggedNextItem() {
        val queuePlayer = FakeQueuePlayer(currentIndex = 1, itemCount = 3)
        val adapter = Media3SequentialPlaybackAdapter.createForTests { queuePlayer }
        val token = SequentialPlaybackSessionToken(PlaybackSessionVersion("session", 1), "episode-1", PlayerType.TYPE_EXO_PLAYER)
        val nextSource = FakeVideoSource("episode-2")

        adapter.setEventListener(RecordingEventListener())
        adapter.beginSession(token, FakeVideoSource("episode-1"))
        val result =
            adapter.queueNextSource(
                sessionToken = token,
                queueItemId = SequentialPlaybackQueueItemId("7"),
                nextSource = nextSource
            )

        assertTrue(result.isSuccess)
        assertEquals(listOf(IntRange(0, 0), IntRange(1, 1)), queuePlayer.removedRanges)
        assertEquals(listOf("sequential-session-1-7"), queuePlayer.enqueuedMediaIds)
    }

    @Test
    fun mediaItemTransitionPropagatesSessionTokenAndQueueItemId() {
        val queuePlayer = FakeQueuePlayer(currentIndex = 0, itemCount = 1)
        val listener = RecordingEventListener()
        val adapter = Media3SequentialPlaybackAdapter.createForTests { queuePlayer }
        val token = SequentialPlaybackSessionToken(PlaybackSessionVersion("session", 4), "episode-4", PlayerType.TYPE_EXO_PLAYER)

        adapter.setEventListener(listener)
        adapter.beginSession(token, FakeVideoSource("episode-4"))
        adapter.queueNextSource(
            sessionToken = token,
            queueItemId = SequentialPlaybackQueueItemId("9"),
            nextSource = FakeVideoSource("episode-5")
        )

        queuePlayer.emitTransition("sequential-session-4-9")

        assertEquals(
            listOf(token to SequentialPlaybackQueueItemId("9")),
            listener.transitions
        )
    }

    @Test
    fun invalidateSessionPreventsLateTransitionsFromLeakingState() {
        val queuePlayer = FakeQueuePlayer(currentIndex = 0, itemCount = 1)
        val listener = RecordingEventListener()
        val adapter = Media3SequentialPlaybackAdapter.createForTests { queuePlayer }
        val token = SequentialPlaybackSessionToken(PlaybackSessionVersion("session", 8), "episode-8", PlayerType.TYPE_EXO_PLAYER)

        adapter.setEventListener(listener)
        adapter.beginSession(token, FakeVideoSource("episode-8"))
        adapter.queueNextSource(
            sessionToken = token,
            queueItemId = SequentialPlaybackQueueItemId("3"),
            nextSource = FakeVideoSource("episode-9")
        )
        adapter.invalidateSession(token)

        queuePlayer.emitTransition("sequential-session-8-3")

        assertTrue(listener.transitions.isEmpty())
    }

    @Test
    fun invalidationReportsAlreadyActiveItemBeforeDelayedCallback() {
        val player = FakeQueuePlayer(0, 1)
        val adapter = Media3SequentialPlaybackAdapter.createForTests { player }
        val token = SequentialPlaybackSessionToken(PlaybackSessionVersion("session", 1), "same", PlayerType.TYPE_EXO_PLAYER)
        val old = FakeVideoSource("same")
        val next = FakeVideoSource("same")
        adapter.beginSession(token, old)
        adapter.queueNextSource(token, SequentialPlaybackQueueItemId("2"), next)
        player.currentId = "sequential-session-1-2"
        assertEquals(SequentialQueueRetirement.CURRENT, adapter.invalidateSession(token))
        assertSame(next, adapter.currentSource())
        assertTrue(adapter.isAttached(next))
        assertFalse(adapter.isAttached(old))
    }

    @Test fun failedRemovalRetainsBorrowedSourceAndCanBeReconciledAgain() {
        val player = FakeQueuePlayer(0, 1)
        val adapter = Media3SequentialPlaybackAdapter.createForTests { player }
        val token = SequentialPlaybackSessionToken(PlaybackSessionVersion("session", 1), "first", PlayerType.TYPE_EXO_PLAYER)
        val next = FakeVideoSource("second")
        adapter.beginSession(token, FakeVideoSource("first"))
        adapter.queueNextSource(token, SequentialPlaybackQueueItemId("candidate"), next)
        player.failRemoval = true
        assertEquals(SequentialQueueRetirement.STILL_ATTACHED, adapter.invalidateSession(token))
        assertTrue(adapter.isAttached(next))
        player.failRemoval = false
        assertEquals(SequentialQueueRetirement.DETACHED, adapter.invalidateSession(token))
        assertFalse(adapter.isAttached(next))
        adapter.onBackendDetached()
        assertEquals(null, adapter.currentSource())
    }

    @Test
    fun nativeTransitionRemovesPreviousItemBeforeNotifyingOwner() {
        val player = FakeQueuePlayer(0, 1)
        val adapter = Media3SequentialPlaybackAdapter.createForTests { player }
        val token = SequentialPlaybackSessionToken(PlaybackSessionVersion("session", 1), "first", PlayerType.TYPE_EXO_PLAYER)
        adapter.beginSession(token, FakeVideoSource("first"))
        adapter.queueNextSource(token, SequentialPlaybackQueueItemId("2"), FakeVideoSource("second"))
        var notifications = 0
        adapter.setEventListener(
            object : SequentialPlaybackBackendAdapter.EventListener {
                override fun onTransitionToQueuedItem(
                    sessionToken: SequentialPlaybackSessionToken,
                    queueItemId: SequentialPlaybackQueueItemId
                ) {
                    assertEquals(1, player.mediaItemCount())
                    notifications++
                }
            }
        )
        player.emitTransition("sequential-session-1-2")
        player.emitTransition("sequential-session-1-2")
        assertEquals(1, notifications)
    }

    @Test fun transitionWithFailedOldItemRemovalStillReportsCurrentAndRetainsBorrowedSources() {
        val player = FakeQueuePlayer(0, 1)
        val adapter = Media3SequentialPlaybackAdapter.createForTests { player }
        val token = SequentialPlaybackSessionToken(PlaybackSessionVersion("session", 1), "first", PlayerType.TYPE_EXO_PLAYER)
        val old = FakeVideoSource("first")
        val next = FakeVideoSource("second")
        val listener = RecordingEventListener()
        adapter.beginSession(token, old)
        adapter.setEventListener(listener)
        adapter.queueNextSource(token, SequentialPlaybackQueueItemId("2"), next)
        player.failRemoval = true
        player.emitTransition("sequential-session-1-2")
        assertEquals(listOf(token to SequentialPlaybackQueueItemId("2")), listener.transitions)
        assertEquals(SequentialQueueRetirement.CURRENT, adapter.invalidateSession(token))
        assertSame(next, adapter.currentSource())
        adapter.beginSession(token.copy(version = token.version.copy(sourceRevision = 2)), next)
        assertTrue(adapter.isAttached(old))
        assertTrue(adapter.isAttached(next))
        player.failRemoval = false
        adapter.beginSession(token.copy(version = token.version.copy(sourceRevision = 2)), next)
        assertFalse(adapter.isAttached(old))
        assertTrue(adapter.isAttached(next))
    }

    private class RecordingEventListener : SequentialPlaybackBackendAdapter.EventListener {
        val transitions = mutableListOf<Pair<SequentialPlaybackSessionToken, SequentialPlaybackQueueItemId>>()

        override fun onTransitionToQueuedItem(
            sessionToken: SequentialPlaybackSessionToken,
            queueItemId: SequentialPlaybackQueueItemId
        ) {
            transitions += sessionToken to queueItemId
        }
    }

    private class FakeQueuePlayer(
        private var currentIndex: Int,
        private var itemCount: Int
    ) : Media3SequentialPlaybackAdapter.Media3QueuePlayer {
        override val identity: Any = Any()

        private var transitionListener: ((String?) -> Unit)? = null

        val removedRanges = mutableListOf<IntRange>()
        val enqueuedMediaIds = mutableListOf<String>()
        var failRemoval = false

        override fun setTransitionListener(listener: ((String?) -> Unit)?) {
            transitionListener = listener
        }

        var currentId: String? = null

        override fun currentMediaId(): String? = currentId

        override fun currentMediaItemIndex(): Int = currentIndex

        override fun mediaItemCount(): Int = itemCount

        override fun removeMediaItems(
            fromIndex: Int,
            toIndex: Int
        ) {
            if (failRemoval) error("fixture remove failure")
            removedRanges += IntRange(fromIndex, toIndex - 1)
            itemCount -= (toIndex - fromIndex)
            if (fromIndex == 0) {
                currentIndex = (currentIndex - (toIndex - fromIndex)).coerceAtLeast(0)
            }
        }

        override fun enqueueNextSource(
            nextSource: BaseVideoSource,
            mediaId: String
        ) {
            enqueuedMediaIds += mediaId
            itemCount += 1
        }

        fun emitTransition(mediaId: String) {
            currentId = mediaId
            currentIndex = (itemCount - 1).coerceAtLeast(0)
            transitionListener?.invoke(mediaId)
        }
    }

    private class FakeVideoSource(
        private val key: String
    ) : BaseVideoSource(0, listOf(key)) {
        override fun indexTitle(index: Int): String = key

        override fun getVideoUrl(): String = "file:///$key.mp4"

        override fun getVideoTitle(): String = key

        override fun getCurrentPosition(): Long = 0L

        override fun getMediaType(): MediaType = MediaType.LOCAL_STORAGE

        override fun getUniqueKey(): String = key

        override fun getStorageId(): Int = 1
    }
}
