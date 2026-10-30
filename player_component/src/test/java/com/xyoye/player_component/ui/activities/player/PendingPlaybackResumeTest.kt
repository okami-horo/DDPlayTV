package com.xyoye.player_component.ui.activities.player

import com.xyoye.player_component.playback.session.PlaybackPositionConstraints
import org.junit.Assert.*
import org.junit.Test

class PendingPlaybackResumeTest {
    @Test fun highProgressAndUnknownDurationKeepExplicitPosition() {
        val source = PlayerUiTestSource("active")
        val pending = PendingPlaybackResume()
        for (position in listOf(90_000L, 95_000L, 99_999L)) {
            pending.prepare(source, position)
            assertEquals(position, pending.take(source, PlaybackPositionConstraints(durationMs = 100_000)))
        }
        pending.prepare(source, 95_000)
        assertEquals(95_000L, pending.take(source, PlaybackPositionConstraints()))
        assertNull(pending.take(source, PlaybackPositionConstraints()))
    }

    @Test fun zeroOverridesOldHistoryAndBoundsUsePreparedCapabilities() {
        val source = PlayerUiTestSource("active", historyPosition = 73_000)
        val pending = PendingPlaybackResume()
        pending.prepare(source, 0)
        assertEquals(0L, pending.take(source, PlaybackPositionConstraints()))
        pending.prepare(source, -10)
        assertEquals(0L, pending.take(source, PlaybackPositionConstraints()))
        pending.prepare(source, 200)
        assertEquals(99L, pending.take(source, PlaybackPositionConstraints(durationMs = 100)))
        pending.prepare(source, 90)
        assertEquals(0L, pending.take(source, PlaybackPositionConstraints(seekable = false)))
    }

    @Test fun oldSourceCallbackAndDetachedBackendCannotConsumeCurrentResume() {
        val old = PlayerUiTestSource("old")
        val current = PlayerUiTestSource("current")
        val pending = PendingPlaybackResume()
        pending.prepare(old, 5_000)
        pending.clear()
        pending.prepare(current, 95_000)
        assertNull(pending.take(old, PlaybackPositionConstraints()))
        assertEquals(95_000L, pending.take(current, PlaybackPositionConstraints()))
        pending.prepare(current, 99_000)
        pending.clear()
        assertNull(pending.take(current, PlaybackPositionConstraints()))
    }
}
