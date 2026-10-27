package com.xyoye.player_component.service

import android.content.Context
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.playback.handoff.PlaybackCommand
import com.xyoye.common_component.playback.handoff.PlaybackDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackStateView
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.handoff.PlaybackVerificationAnswer
import com.xyoye.common_component.playback.handoff.PlaybackVerificationChallenge
import com.xyoye.common_component.playback.handoff.PlaybackVerificationResult
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import com.xyoye.common_component.source.handoff.PlaybackSourceResolver
import com.xyoye.common_component.source.handoff.PlaybackVerificationResolution
import com.xyoye.common_component.source.handoff.ResolvedPlaybackVerification
import com.xyoye.player_component.playback.handoff.HandoffStoreFailureKind
import com.xyoye.player_component.playback.handoff.HandoffStoreResult
import com.xyoye.player_component.playback.handoff.PlaybackHandoffStore
import com.xyoye.player_component.playback.session.ActivePlaybackSession
import com.xyoye.player_component.playback.session.PlaybackSessionBackend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

@Route(path = RouteTable.Player.PlaybackRequestService, name = "Playback requests")
class PlaybackRequestServiceImpl internal constructor(
    private val store: PlaybackHandoffStore,
    private val registerVerification: suspend (
        PlaybackDescriptor,
        String
    ) -> PlaybackVerificationResolution<ResolvedPlaybackVerification> = { descriptor, voucher ->
        sharedResolver.registerVerification(descriptor, voucher)
    },
    private val validateVerification: suspend (
        PlaybackDescriptor,
        ResolvedPlaybackVerification,
        PlaybackVerificationAnswer
    ) -> PlaybackVerificationResolution<Unit> = { descriptor, registration, answer ->
        sharedResolver.validateVerification(descriptor, registration, answer)
    },
    private val verificationMutex: Mutex = sharedVerificationMutex,
    private val resolve: suspend (PlaybackDescriptor) -> PlaybackResolutionResult
) : PlaybackRequestService {
    constructor() : this(sharedStore, resolve = { sharedResolver.resolve(it) })

    override fun init(context: Context?) = Unit

    override suspend fun submit(input: PlaybackRequestInput): PlaybackSubmissionResult =
        when (val result = store.submit(input)) {
            is HandoffStoreResult.Success -> PlaybackSubmissionResult.Accepted(result.value.requestId)
            is HandoffStoreResult.Failure -> PlaybackSubmissionResult.Rejected(PlaybackPreparationFailure.RecoveryStatePersistenceFailed)
        }

    internal fun createSession(
        backend: PlaybackSessionBackend,
        playbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): ActivePlaybackSession {
        val registration = Any()
        activeRegistration = registration
        state.value = null
        return ActivePlaybackSession(
            store,
            backend,
            resolve,
            playbackDispatcher,
            cleanupDispatcher,
            resolveReplacement = { descriptor, profile, intent -> sharedResolver.resolve(descriptor, profile, intent) },
            onStateChanged = { if (activeRegistration === registration) state.value = it },
            isCurrentOwner = { activeRegistration === registration }
        ).also { activeSession = WeakReference(it) }
    }

    override fun observePlayback(): StateFlow<PlaybackStateView?> = state.asStateFlow()

    override suspend fun dispatch(command: PlaybackCommand): PlaybackOperationResult =
        activeSession.get()?.dispatch(command)
            ?: PlaybackOperationResult.Rejected(PlaybackPreparationFailure.SessionSnapshotMissing)

    override suspend fun verificationChallenge(verificationId: String): PlaybackVerificationResult =
        verificationMutex.withLock {
            val read = store.readVerification(verificationId)
            if (read is HandoffStoreResult.Failure) return@withLock PlaybackVerificationResult.Rejected(read.failure())
            val verification = (read as HandoffStoreResult.Success).value
            val payload = verification.payload
            if (payload.gt != null && payload.challenge != null && payload.registerToken != null) {
                return@withLock PlaybackVerificationResult.Ready(PlaybackVerificationChallenge(payload.gt, payload.challenge))
            }
            val pending = store.readPending(verification.requestId)
            if (pending is HandoffStoreResult.Failure) return@withLock PlaybackVerificationResult.Rejected(pending.failure())
            when (val registered = registerVerification((pending as HandoffStoreResult.Success).value.input.descriptor, payload.voucher)) {
                is PlaybackVerificationResolution.Failure -> PlaybackVerificationResult.Rejected(registered.failure)
                is PlaybackVerificationResolution.Success -> {
                    val data = registered.value
                    when (
                        val saved =
                            store.updateVerification(
                                verificationId,
                                payload.copy(gt = data.gt, challenge = data.challenge, registerToken = data.registerToken)
                            )
                    ) {
                        is HandoffStoreResult.Success ->
                            PlaybackVerificationResult.Ready(
                                PlaybackVerificationChallenge(data.gt, data.challenge)
                            )
                        is HandoffStoreResult.Failure -> PlaybackVerificationResult.Rejected(saved.failure())
                    }
                }
            }
        }

    override suspend fun completeVerification(
        verificationId: String,
        answer: PlaybackVerificationAnswer
    ): PlaybackSubmissionResult =
        verificationMutex.withLock {
            val read = store.readVerification(verificationId)
            if (read is HandoffStoreResult.Failure) return@withLock PlaybackSubmissionResult.Rejected(read.failure())
            val verification = (read as HandoffStoreResult.Success).value
            val payload = verification.payload
            if (payload.validated) return@withLock PlaybackSubmissionResult.Accepted(verification.requestId)
            val gt = payload.gt ?: return@withLock PlaybackSubmissionResult.Rejected(PlaybackPreparationFailure.AuthenticationRequired)
            val challenge =
                payload.challenge ?: return@withLock PlaybackSubmissionResult.Rejected(PlaybackPreparationFailure.AuthenticationRequired)
            val token =
                payload.registerToken
                    ?: return@withLock PlaybackSubmissionResult.Rejected(PlaybackPreparationFailure.AuthenticationRequired)
            val pending = store.readPending(verification.requestId)
            if (pending is HandoffStoreResult.Failure) return@withLock PlaybackSubmissionResult.Rejected(pending.failure())
            when (
                val validated =
                    validateVerification(
                        (pending as HandoffStoreResult.Success).value.input.descriptor,
                        ResolvedPlaybackVerification(gt, challenge, token),
                        answer
                    )
            ) {
                is PlaybackVerificationResolution.Failure -> PlaybackSubmissionResult.Rejected(validated.failure)
                is PlaybackVerificationResolution.Success ->
                    when (val saved = store.updateVerification(verificationId, payload.copy(validated = true))) {
                        is HandoffStoreResult.Success -> PlaybackSubmissionResult.Accepted(verification.requestId)
                        is HandoffStoreResult.Failure -> PlaybackSubmissionResult.Rejected(saved.failure())
                    }
            }
        }

    override suspend fun cancelVerification(verificationId: String): PlaybackOperationResult {
        val verification = store.readVerification(verificationId)
        if (verification is HandoffStoreResult.Failure) return PlaybackOperationResult.Rejected(verification.failure())
        return when (store.clearPending((verification as HandoffStoreResult.Success).value.requestId)) {
            is HandoffStoreResult.Success -> PlaybackOperationResult.Success
            is HandoffStoreResult.Failure -> PlaybackOperationResult.Rejected(PlaybackPreparationFailure.RecoveryStatePersistenceFailed)
        }
    }

    private fun HandoffStoreResult.Failure.failure(): PlaybackPreparationFailure =
        when (failure.kind) {
            HandoffStoreFailureKind.REQUEST_EXPIRED -> PlaybackPreparationFailure.RequestExpired
            HandoffStoreFailureKind.REQUEST_MISSING, HandoffStoreFailureKind.VERIFICATION_MISSING -> PlaybackPreparationFailure.RequestMissing
            HandoffStoreFailureKind.REQUEST_SUPERSEDED, HandoffStoreFailureKind.STALE_SESSION -> PlaybackPreparationFailure.RequestSuperseded
            HandoffStoreFailureKind.SENSITIVE_PAYLOAD_UNAVAILABLE -> PlaybackPreparationFailure.SensitivePayloadUnavailable
            else -> PlaybackPreparationFailure.RecoveryStatePersistenceFailed
        }

    private companion object {
        val sharedStore by lazy { PlaybackHandoffStore() }
        val sharedResolver by lazy { PlaybackSourceResolver() }
        val state = MutableStateFlow<PlaybackStateView?>(null)

        // The ViewModel owns the runtime. The process provider must not keep its Activity/kernel alive.
        @Volatile var activeSession = WeakReference<ActivePlaybackSession>(null)

        @Volatile var activeRegistration: Any? = null
        val sharedVerificationMutex = Mutex()
    }
}
