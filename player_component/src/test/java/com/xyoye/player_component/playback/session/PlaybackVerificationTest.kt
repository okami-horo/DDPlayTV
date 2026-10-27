package com.xyoye.player_component.playback.session

import com.xyoye.common_component.playback.handoff.PlaybackDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.handoff.PlaybackVerificationAnswer
import com.xyoye.common_component.playback.handoff.PlaybackVerificationResult
import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import com.xyoye.common_component.source.handoff.PlaybackVerificationResolution
import com.xyoye.common_component.source.handoff.ResolvedPlaybackVerification
import com.xyoye.player_component.playback.handoff.PlaybackHandoffStore
import com.xyoye.player_component.service.PlaybackRequestServiceImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
class PlaybackVerificationTest {
    @Test fun verificationKeepsOldPlaybackAndRetriesSameIdWithoutExtendingItsTtl() =
        runTest {
            val g = Fixture(StandardTestDispatcher(testScheduler))
            g.session.deliver(g.f.submit("old"))
            val (requestId, verificationId) = g.begin()
            val expiry =
                g.f
                    .state()
                    .pending!!
                    .expiresAtMs
            assertEquals(listOf("old"), g.f.backend.applied)
            assertEquals(
                0,
                g.f.leases
                    .single()
                    .releases
            )
            assertFalse(
                g.f.blob.data!!
                    .contains("private-voucher")
            )
            val ready = g.provider.verificationChallenge(verificationId) as PlaybackVerificationResult.Ready
            assertEquals("gt", ready.challenge.gt)
            assertFalse(ready.toString().contains("private-token"))
            g.f.now += 1000
            assertEquals(
                requestId,
                (g.provider.completeVerification(verificationId, answer) as PlaybackSubmissionResult.Accepted).requestId
            )
            assertEquals(
                expiry,
                g.f
                    .state()
                    .pending!!
                    .expiresAtMs
            )
            assertTrue(g.session.deliver(requestId) is SessionRequestResult.Activated)
            assertNull(g.f.state().verification)
            assertEquals(listOf("old", "verified"), g.f.backend.applied)
            assertEquals(listOf(1, 0), g.f.leases.map { it.releases })
            g.session.exit()
        }

    @Test fun recreatedProviderUsesEncryptedRegistrationAndConcurrentCompletionValidatesOnce() =
        runTest {
            val g = Fixture(StandardTestDispatcher(testScheduler))
            val (requestId, verificationId) = g.begin()
            g.provider.verificationChallenge(verificationId)
            val recreated = g.provider()
            assertTrue(recreated.verificationChallenge(verificationId) is PlaybackVerificationResult.Ready)
            assertEquals(1, g.registrations)
            val first = async { g.provider.completeVerification(verificationId, answer) }
            val second = async { recreated.completeVerification(verificationId, answer) }
            assertEquals(requestId, (first.await() as PlaybackSubmissionResult.Accepted).requestId)
            assertEquals(requestId, (second.await() as PlaybackSubmissionResult.Accepted).requestId)
            assertEquals(1, g.validations)
            assertNotNull(g.f.state().pending)
            assertTrue(
                g.f.backend.applied
                    .isEmpty()
            )
            g.provider.cancelVerification(verificationId)
            g.session.exit()
        }

    @Test fun replacementDuringRegistrationRejectsLateChallengeAndPreservesNewPending() =
        runTest {
            val g = Fixture(StandardTestDispatcher(testScheduler))
            val (_, verificationId) = g.begin()
            val ready = CompletableDeferred<Unit>()
            g.register = { _, _ ->
                ready.await()
                PlaybackVerificationResolution.Success(g.registration)
            }
            val reading = async { g.provider.verificationChallenge(verificationId) }
            runCurrent()
            val next = g.f.submit("new")
            ready.complete(Unit)
            assertTrue(reading.await() is PlaybackVerificationResult.Rejected)
            assertEquals(
                next,
                g.f
                    .state()
                    .pending!!
                    .requestId
            )
            assertNull(g.f.state().verification)
            assertTrue(
                g.f.backend.applied
                    .isEmpty()
            )
            g.session.exit()
        }

    @Test fun cancellationDuringValidationCannotResumePlayback() =
        runTest {
            val g = Fixture(StandardTestDispatcher(testScheduler))
            val (_, verificationId) = g.begin()
            g.provider.verificationChallenge(verificationId)
            val ready = CompletableDeferred<Unit>()
            g.validate = { _, _, _ ->
                ready.await()
                PlaybackVerificationResolution.Success(Unit)
            }
            val verifying = async { g.provider.completeVerification(verificationId, answer) }
            runCurrent()
            assertEquals(PlaybackOperationResult.Success, g.provider.cancelVerification(verificationId))
            ready.complete(Unit)
            assertTrue(verifying.await() is PlaybackSubmissionResult.Rejected)
            assertNull(g.f.state().pending)
            assertTrue(
                g.f.backend.applied
                    .isEmpty()
            )
            g.session.exit()
        }

    @Test fun expiryDuringValidationRejectsLateSuccessWithoutRefreshingTtl() =
        runTest {
            val g = Fixture(StandardTestDispatcher(testScheduler))
            val (_, verificationId) = g.begin()
            g.provider.verificationChallenge(verificationId)
            g.validate = { _, _, _ ->
                g.f.now += PlaybackHandoffStore.PENDING_TTL_MS
                PlaybackVerificationResolution.Success(Unit)
            }
            val result = g.provider.completeVerification(verificationId, answer) as PlaybackSubmissionResult.Rejected
            assertEquals(PlaybackPreparationFailure.RequestExpired, result.failure)
            assertNull(g.f.state().pending)
            assertTrue(
                g.f.backend.applied
                    .isEmpty()
            )
            g.session.exit()
        }

    @Test fun cancellationOfChallengeFetchPropagatesAndRetainsRetryablePending() =
        runTest {
            val g = Fixture(StandardTestDispatcher(testScheduler))
            val (_, verificationId) = g.begin()
            g.register = { _, _ -> awaitCancellation() }
            val fetching = launch { g.provider.verificationChallenge(verificationId) }
            runCurrent()
            fetching.cancelAndJoin()
            assertNotNull(g.f.state().pending)
            assertNull(
                g.f
                    .state()
                    .verification!!
                    .payload.registerToken
            )
            g.register = { _, _ -> PlaybackVerificationResolution.Success(g.registration) }
            assertTrue(g.provider.verificationChallenge(verificationId) is PlaybackVerificationResult.Ready)
            g.provider.cancelVerification(verificationId)
            g.session.exit()
        }

    @Test fun failedChallengePersistenceDoesNotExposeAUsableChallengeOrEraseOldPlayback() =
        runTest {
            val g = Fixture(StandardTestDispatcher(testScheduler))
            g.session.deliver(g.f.submit("old"))
            val (_, verificationId) = g.begin()
            g.f.cipher.fail = true
            val result = g.provider.verificationChallenge(verificationId) as PlaybackVerificationResult.Rejected
            assertEquals(PlaybackPreparationFailure.RecoveryStatePersistenceFailed, result.failure)
            assertNull(
                g.f
                    .state()
                    .verification!!
                    .payload.registerToken
            )
            assertEquals(listOf("old"), g.f.backend.applied)
            assertEquals(
                0,
                g.f.leases
                    .single()
                    .releases
            )
            g.f.cipher.fail = false
            g.provider.cancelVerification(verificationId)
            g.session.exit()
        }

    private val answer = PlaybackVerificationAnswer(null, "validate", "seccode")

    private class Fixture(
        dispatcher: CoroutineDispatcher
    ) {
        val f = PlaybackSessionTestFixture(dispatcher)
        val registration = ResolvedPlaybackVerification("gt", "challenge", "private-token")
        var registrations = 0
        var validations = 0
        var verified = false
        val mutex = Mutex()
        var register: suspend (PlaybackDescriptor, String) -> PlaybackVerificationResolution<ResolvedPlaybackVerification> = { _, voucher ->
            assertEquals("private-voucher", voucher)
            registrations++
            PlaybackVerificationResolution.Success(registration)
        }
        var validate: suspend (
            PlaybackDescriptor,
            ResolvedPlaybackVerification,
            PlaybackVerificationAnswer
        ) -> PlaybackVerificationResolution<Unit> = { _, data, _ ->
            assertEquals("private-token", data.registerToken)
            validations++
            verified = true
            PlaybackVerificationResolution.Success(Unit)
        }
        val provider = provider()
        val session = provider.createSession(f.backend, dispatcher, dispatcher)

        fun provider() =
            PlaybackRequestServiceImpl(
                f.store,
                registerVerification = { a, b -> register(a, b) },
                validateVerification = { a, b, c -> validate(a, b, c) },
                verificationMutex = mutex,
                resolve = { f.resolve(it) }
            )

        suspend fun begin(): Pair<String, String> {
            f.resolve = {
                if (verified) {
                    PlaybackResolutionResult.Success(f.resolved(it))
                } else {
                    PlaybackResolutionResult.VerificationRequired("private-voucher")
                }
            }
            val id = f.submit("verified")
            val result = session.deliver(id) as SessionRequestResult.Rejected
            val verification = result.failure as PlaybackPreparationFailure.InteractiveVerificationRequired
            return id to verification.verificationId
        }
    }
}
