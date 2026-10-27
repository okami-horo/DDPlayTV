package com.xyoye.player_component.playback.session

import android.content.Intent
import android.os.Bundle
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
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
class PlaybackRoutingTest {
    @Test fun copiedIntentIdsAloneCannotAuthorizeSnapshotRestoration() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val request = f.submit("old")
            f.session.deliver(request)
            val coldSession =
                ActivePlaybackSession(
                    f.store,
                    SessionTestBackend(),
                    f.resolve,
                    StandardTestDispatcher(testScheduler),
                    StandardTestDispatcher(testScheduler)
                )
            val intent = Intent().putExtra(PlaybackNavigation.REQUEST_ID, request).putExtra(PlaybackNavigation.SESSION_ID, request)
            val args = PlaybackActivityArguments.read(intent, null)
            assertNull(args.recovery)
            assertEquals(
                PlaybackPreparationFailure.RequestMissing,
                (coldSession.open(args.requestId, args.recovery) as SessionRequestResult.Rejected).failure
            )
            f.session.exit()
        }

    @Test fun newIntentTakesPriorityOverOldSavedRequestDuringSystemRestoration() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val f = PlaybackSessionTestFixture(dispatcher)
            val old = f.submit("old")
            f.session.deliver(old)
            val saved = Bundle()
            PlaybackActivityArguments.save(
                saved,
                old,
                f.session.runtime!!
                    .version.sessionId
            )
            val fresh = f.submit("new")
            val args = PlaybackActivityArguments.read(Intent().putExtra(PlaybackNavigation.REQUEST_ID, fresh), saved)
            val rebuiltBackend = SessionTestBackend()
            val restored = ActivePlaybackSession(f.store, rebuiltBackend, f.resolve, dispatcher, dispatcher)
            assertTrue(restored.open(args.requestId, args.recovery) is SessionRequestResult.Activated)
            assertEquals("new", rebuiltBackend.current!!.getVideoTitle())
            assertEquals(old, restored.runtime!!.version.sessionId)
            assertEquals(fresh, restored.runtime!!.requestId)
            assertEquals(2L, restored.runtime!!.version.sourceRevision)
            restored.exit()
            f.session.exit()
        }

    @Test fun savedPayloadContainsOnlyBindingIdsAndRetainsOriginalRequest() {
        val state = Bundle()
        PlaybackActivityArguments.save(state, "original-id", "session-id")
        assertEquals(setOf(PlaybackNavigation.REQUEST_ID, PlaybackNavigation.SESSION_ID), state.keySet())
        assertTrue(state.keySet().all { state.get(it) is String })
        val incoming = Intent().putExtra(PlaybackNavigation.REQUEST_ID, "new-id").putExtra("headers", "private-cookie")
        val args = PlaybackActivityArguments.read(incoming, state)
        assertEquals("new-id", args.requestId)
        assertEquals(PlaybackRecoveryContext("session-id", "original-id"), args.recovery)
        assertFalse(args.toString().contains("private-cookie"))
    }
}
