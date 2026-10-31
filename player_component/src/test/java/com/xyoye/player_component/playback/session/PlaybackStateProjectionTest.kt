package com.xyoye.player_component.playback.session

import com.xyoye.common_component.playback.handoff.*
import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import com.xyoye.common_component.source.handoff.ResolvedPlayback
import com.xyoye.data_component.media3.entity.Media3Capability
import com.xyoye.data_component.media3.entity.Media3PlaybackState
import com.xyoye.data_component.media3.entity.Media3SourceType
import com.xyoye.player_component.playback.handoff.HandoffStoreResult
import com.xyoye.player_component.service.PlaybackRequestServiceImpl
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackStateProjectionTest {
    @Test fun localPlaybackPublishesIdentityWithoutAGatewaySession() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val fixture = PlaybackSessionTestFixture(dispatcher)
            val provider = PlaybackRequestServiceImpl(fixture.store, resolve = { fixture.resolve(it) })
            val session = provider.createSession(fixture.backend, dispatcher, dispatcher)
            try {
                session.deliver(fixture.submit("local"))
                val state = provider.observePlayback().value
                assertNotNull("Actual local playback has no published state because gateway creation is disabled", state)
                assertEquals(session.runtime!!.version, state!!.version)
                assertEquals("local", state.source.displayTitle)
            } finally {
                session.exit()
            }
            assertNull(provider.observePlayback().value)
        }

    @Test fun requestedCapabilitiesDoNotGrantUnsupportedOperationsAndOldCommandsAreRejected() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val f = PlaybackSessionTestFixture(dispatcher)
            val provider = PlaybackRequestServiceImpl(f.store, resolve = { f.resolve(it) })
            val session = provider.createSession(f.backend, dispatcher, dispatcher)
            val request =
                f.store.submit(
                    PlaybackRequestInput(
                        f.descriptor("first"),
                        PlaybackLaunchOptions("first", Media3SourceType.STREAM, listOf(Media3Capability.CAST, Media3Capability.PIP)),
                        PlaybackRequestOrigin.STORAGE_FILE
                    )
                ) as HandoffStoreResult.Success
            session.deliver(request.value.requestId)
            val first = provider.observePlayback().value!!
            assertEquals(f.backend.backendState.capabilities, first.capabilities)
            assertFalse(Media3Capability.CAST in first.capabilities)
            assertTrue(
                provider.dispatch(PlaybackCommand(first.version, PlaybackAction.EnterPictureInPicture)) is PlaybackOperationResult.Rejected
            )
            assertEquals(PlaybackOperationResult.Success, provider.dispatch(PlaybackCommand(first.version, PlaybackAction.Pause)))
            assertEquals(Media3PlaybackState.PAUSED, provider.observePlayback().value!!.state)
            assertEquals(PlaybackOperationResult.Success, provider.dispatch(PlaybackCommand(first.version, PlaybackAction.Seek(500))))
            assertEquals(500L, f.backend.progress().positionMs)
            session.deliver(f.submit("second"))
            val second = provider.observePlayback().value!!
            assertEquals(first.version.sourceRevision + 1, second.version.sourceRevision)
            val actions = f.backend.actions.toList()
            assertTrue(provider.dispatch(PlaybackCommand(first.version, PlaybackAction.Pause)) is PlaybackOperationResult.Rejected)
            f.backend.backendState = BackendPlaybackState(Media3PlaybackState.FAILED, setOf(Media3Capability.CAST))
            session.refreshState(f.backend, first.version)
            assertSame(second, provider.observePlayback().value)
            assertEquals(actions, f.backend.actions)
            session.exit()
        }

    @Test fun retiredBackendAndRetiredOwnerCannotOverwriteTheCurrentProjection() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val f = PlaybackSessionTestFixture(dispatcher)
            val provider = PlaybackRequestServiceImpl(f.store, resolve = { f.resolve(it) })
            val session = provider.createSession(f.backend, dispatcher, dispatcher)
            session.deliver(f.submit("old"))
            val first = session.runtime!!.version
            session.unbindBackend(f.backend)
            val replacement =
                SessionTestBackend().apply {
                    backendState = BackendPlaybackState(Media3PlaybackState.PAUSED, setOf(Media3Capability.PLAY))
                }
            session.bindBackend(replacement)
            val rebound = provider.observePlayback().value!!
            session.refreshState(f.backend, first)
            assertSame(rebound, provider.observePlayback().value)
            assertEquals(setOf(Media3Capability.PLAY), rebound.capabilities)
            val newerBackend = SessionTestBackend()
            val newer = provider.createSession(newerBackend, dispatcher, dispatcher)
            newer.deliver(f.submit("new"))
            val current = provider.observePlayback().value!!
            session.refreshState(replacement, first)
            session.exit()
            assertSame(current, provider.observePlayback().value)
            assertEquals("new", current.source.displayTitle)
            assertEquals(PlaybackOperationResult.Success, provider.dispatch(PlaybackCommand(current.version, PlaybackAction.Pause)))
            newer.exit()
            assertNull(provider.observePlayback().value)
        }

    @Test fun commandReconcilesNativeTransitionBeforeCallbackAndPublishesSaveFailure() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val f = PlaybackSessionTestFixture(dispatcher)
            val queue = listOf(f.descriptor("one"), f.descriptor("two"))
            f.resolve = {
                val resolved = f.resolved(it)
                PlaybackResolutionResult.Success(ResolvedPlayback(resolved.source, it, resolved.identity, queue))
            }
            val provider = PlaybackRequestServiceImpl(f.store, resolve = { f.resolve(it) })
            val session = provider.createSession(f.backend, dispatcher, dispatcher)
            session.deliver(f.submit("one"))
            val first = session.runtime!!.version
            assertTrue(session.prepareNextNative(first))
            val queued = f.backend.queued
            assertEquals(PlaybackOperationResult.Success, provider.dispatch(PlaybackCommand(first, PlaybackAction.Pause)))
            assertSame("Pausing must retain the prepared native next item", queued, f.backend.queued)
            assertFalse(f.leases.last().isReleased)
            f.backend.actions.clear()
            f.backend.transition()
            f.blob.failWrite = true
            assertTrue(provider.dispatch(PlaybackCommand(first, PlaybackAction.Pause)) is PlaybackOperationResult.Rejected)
            assertTrue(f.backend.actions.isEmpty())
            val current = provider.observePlayback().value!!
            assertEquals("two", current.source.displayTitle)
            assertEquals(first.sourceRevision + 1, current.version.sourceRevision)
            assertEquals(first.sourceRevision, current.lastPersistedSourceRevision)
            assertTrue(current.recoverySaveFailed)
            f.blob.failWrite = false
            assertEquals(SnapshotSaveResult.SAVED, session.saveProgress(session.captureProgress()!!))
            assertEquals(current.version.sourceRevision, provider.observePlayback().value!!.lastPersistedSourceRevision)
            assertFalse(provider.observePlayback().value!!.recoverySaveFailed)
            session.exit()
        }
}
