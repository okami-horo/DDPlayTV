package com.xyoye.player_component.playback.session

import com.xyoye.common_component.playback.addon.*
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackInternalRecoveryTest {
    private val update = PlaybackSettingUpdate("quality", "720")

    @Test fun successfulReplacementCommitsLatestPositionAndRetiresOnlyOldTransport() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.addonFactory = { Addon(it) }
            val request = f.submit("same")
            f.session.deliver(request)
            val before = f.session.runtime!!
            before.playback.source.setSubtitlePath("subtitle.ass")
            before.playback.source.setDanmu(LocalDanmuBean("danmu.xml", "episode"))
            f.backend.playbackProgress = PlaybackProgress(567, false)
            val pending = f.submit("external")
            f.backend.beforeApply = { next, version ->
                assertEquals(
                    567L,
                    f
                        .state()
                        .snapshot!!
                        .target.positionMs
                )
                assertEquals(
                    version,
                    f
                        .state()
                        .snapshot!!
                        .target.version
                )
                assertEquals("subtitle.ass", next.source.getSubtitlePath())
                assertEquals(before.playback.source.getDanmu(), next.source.getDanmu())
                assertEquals(0, f.leases.first().releases)
            }
            val result = f.session.applySetting(update, before.version) as SessionRequestResult.Activated
            assertEquals(before.version.copy(sourceRevision = 2), result.version)
            assertEquals(request, f.session.runtime!!.requestId)
            assertEquals(pending, f.state().pending!!.requestId)
            assertEquals(PlaybackProgress(567, false), f.backend.progress())
            assertEquals(listOf(1, 0), f.leases.map { it.releases })
            f.session.exit()
        }

    @Test fun saveFailurePreservesTheActiveSourceAndReleasesOnlyItsReplacement() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.addonFactory = { Addon(it) }
            f.session.deliver(f.submit("same"))
            val before = f.session.runtime!!
            f.blob.failWrite = true
            val result = f.session.applySetting(update, before.version) as SessionRequestResult.Rejected
            assertEquals(PlaybackPreparationFailure.RecoveryStatePersistenceFailed, result.failure)
            assertSame(before, f.session.runtime)
            assertSame(before.playback.source, f.backend.current)
            assertEquals(listOf(0, 1), f.leases.map { it.releases })
            f.blob.failWrite = false
            f.session.exit()
        }

    @Test fun lateNonCooperativeSettingDecisionCannotCancelTheNewerRequest() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            val gate = CompletableDeferred<Unit>()
            f.addonFactory = { identity -> Addon(identity) { withContext(NonCancellable) { gate.await() } } }
            f.session.deliver(f.submit("old"))
            val first = f.session.runtime!!
            val slow = async { f.session.applySetting(update, first.version) }
            runCurrent()
            f.session.deliver(f.submit("new"))
            gate.complete(Unit)
            slow.join()
            assertTrue(slow.isCancelled)
            assertEquals("new", f.backend.current!!.getVideoTitle())
            assertEquals(2, f.leases.size)
            f.session.exit()
        }

    @Test fun latePreparedRecoveryIsReleasedWithoutChangingNewerPlayback() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.addonFactory = { Addon(it) }
            val gate = CompletableDeferred<Unit>()
            f.session.deliver(f.submit("old"))
            val before = f.session.runtime!!
            f.resolveReplacement = { descriptor, _, _ ->
                val resolved = f.resolved(descriptor)
                withContext(NonCancellable) { gate.await() }
                PlaybackResolutionResult.Success(resolved)
            }
            val slow = async { f.session.recover(recovery(before), before.version) }
            runCurrent()
            f.session.deliver(f.submit("new"))
            gate.complete(Unit)
            slow.join()
            assertTrue(slow.isCancelled)
            assertEquals("new", f.backend.current!!.getVideoTitle())
            assertEquals(listOf(1, 1, 0), f.leases.map { it.releases })
            f.session.exit()
        }

    @Test fun internalVerificationFailureDoesNotReviveConsumedPending() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.addonFactory = { Addon(it) }
            f.session.deliver(f.submit("same"))
            val before = f.session.runtime!!
            f.resolveReplacement = { _, _, _ -> PlaybackResolutionResult.VerificationRequired("sensitive-voucher") }
            val result = f.session.recover(recovery(before), before.version) as SessionRequestResult.Rejected
            assertEquals(PlaybackPreparationFailure.AuthenticationRequired, result.failure)
            assertSame(before, f.session.runtime)
            assertNull(f.state().pending)
            assertNull(f.state().verification)
            assertEquals(0, f.leases.single().releases)
            f.session.exit()
        }

    @Test fun preparationFailureOrWrongResolvedIdentityPreservesTheActiveSource() =
        runTest {
            for (wrongIdentity in listOf(false, true)) {
                val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
                f.addonFactory = { Addon(it) }
                f.session.deliver(f.submit("same"))
                val before = f.session.runtime!!
                f.resolveReplacement = { _, _, _ ->
                    if (wrongIdentity) {
                        PlaybackResolutionResult.Success(f.resolved(f.descriptor("wrong")))
                    } else {
                        PlaybackResolutionResult.Failure(PlaybackPreparationFailure.NetworkUnavailable)
                    }
                }
                assertTrue(f.session.applySetting(update, before.version) is SessionRequestResult.Rejected)
                assertSame(before, f.session.runtime)
                assertTrue(f.backend.progress().playWhenReady)
                assertEquals(0, f.leases.first().releases)
                if (wrongIdentity) assertEquals(1, f.leases.last().releases)
                f.session.exit()
            }
        }

    @Test fun kernelFallbackReusesLeaseAndRevisionAndNonMedia3SettingsWaitForReplay() =
        runTest {
            val f = PlaybackSessionTestFixture(StandardTestDispatcher(testScheduler))
            f.addonFactory = { Addon(it) }
            f.session.deliver(f.submit("same"))
            val before = f.session.runtime!!
            val snapshot = f.blob.data
            before.playback.source.setSubtitlePath("retained.ass")
            f.backend.playbackProgress = PlaybackProgress(900, false)
            val fallback = PlaybackProfile(PlayerType.TYPE_VLC_PLAYER, PlaybackProfileSource.FALLBACK)
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
            assertEquals(SessionRequestResult.SettingsSaved, f.session.applySetting(update, before.version))
            assertEquals(1, f.leases.size)
            assertEquals(0, f.leases.single().releases)
            f.session.deliver(f.submit("new"))
            assertTrue(f.session.fallbackKernel(before.version, fallback) is SessionRequestResult.Rejected)
            assertEquals("new", f.backend.current!!.getVideoTitle())
            f.session.exit()
        }

    private fun recovery(active: ActivePlayback): PlaybackRecoveryRequest =
        PlaybackRecoveryRequest(
            (active.playback.source.getPlaybackAddon() as Addon).identity,
            345,
            null
        )

    private class Addon(
        val identity: PlaybackIdentity,
        val beforeDecision: suspend () -> Unit = {}
    ) : PlaybackPreferenceSwitchableAddon,
        PlaybackUrlRecoverableAddon,
        PlaybackReleasableAddon {
        override val addonId = "recovery-fixture"

        override fun onEvent(event: PlaybackEvent) = Unit

        override fun onRelease() = Unit

        override suspend fun applySettingUpdate(
            update: PlaybackSettingUpdate,
            positionMs: Long
        ): Result<PlaybackReplacementDecision> {
            beforeDecision()
            return Result.success(
                PlaybackReplacementDecision.Prepare(
                    PlaybackReplacementIntent(identity, "active-token", PlaybackReplacementOperation.ApplyPreferences)
                )
            )
        }

        override suspend fun recover(request: PlaybackRecoveryRequest): Result<PlaybackReplacementDecision> =
            Result.success(
                PlaybackReplacementDecision.Prepare(
                    PlaybackReplacementIntent(
                        identity,
                        "active-token",
                        PlaybackReplacementOperation.Recover(null, null, false, request.positionMs)
                    )
                )
            )
    }
}
