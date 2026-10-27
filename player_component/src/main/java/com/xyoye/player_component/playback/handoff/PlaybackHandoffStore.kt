package com.xyoye.player_component.playback.handoff

import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Single-process read/modify/write of one encrypted value; no cache or second runtime owner. */
internal class PlaybackHandoffStore(
    private val storage: HandoffBlobStorage = MmkvHandoffStorage(),
    private val cipher: HandoffCipher = KeystoreHandoffCipher(),
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()
    private val codec = PlaybackHandoffCodec()

    suspend fun submit(input: PlaybackRequestInput): HandoffStoreResult<PendingPlaybackRequest> {
        // Detach producer-owned collections before the first suspension.
        val captured =
            try {
                codec.copy(input)
            } catch (_: Exception) {
                return invalidPayload()
            }
        return transaction { state ->
            val now = wallTime()
            val pending = PendingPlaybackRequest(newId(), captured, now, now + PENDING_TTL_MS)
            persist(state.copy(pending = pending, verification = null))
            pending
        }
    }

    suspend fun readPending(requestId: String): HandoffStoreResult<PendingPlaybackRequest> =
        transaction { state ->
            pending(state, requestId)
        }

    suspend fun startVerification(
        requestId: String,
        payload: PlaybackVerificationPayload
    ): HandoffStoreResult<PendingPlaybackVerification> =
        transaction { state ->
            pending(state, requestId)
            val verification = PendingPlaybackVerification(requestId, newId(), payload)
            persist(state.copy(verification = verification))
            verification
        }

    suspend fun readVerification(verificationId: String): HandoffStoreResult<PendingPlaybackVerification> =
        transaction { state ->
            verification(state, verificationId)
        }

    suspend fun updateVerification(
        verificationId: String,
        payload: PlaybackVerificationPayload
    ): HandoffStoreResult<PendingPlaybackVerification> =
        transaction { state ->
            val current = verification(state, verificationId)
            val updated = current.copy(payload = payload)
            persist(state.copy(verification = updated))
            updated
        }

    suspend fun activate(
        requestId: String,
        target: PlaybackSnapshotTarget,
        stillCurrent: () -> Boolean
    ): HandoffStoreResult<PlaybackSnapshotRecord> {
        val captured =
            try {
                codec.copy(target)
            } catch (_: Exception) {
                return invalidPayload()
            }
        return transaction { state ->
            pending(state, requestId)
            if (captured.requestId != requestId) fail(HandoffStoreFailureKind.INVALID_PAYLOAD)
            if (!stillCurrent()) fail(HandoffStoreFailureKind.STALE_SESSION)
            val snapshot = snapshot(captured)
            persist(state.copy(pending = null, verification = null, snapshot = snapshot))
            snapshot
        }
    }

    suspend fun readSnapshot(sessionId: String): HandoffStoreResult<PlaybackSnapshotRecord> = readSnapshotForRecovery(sessionId, null)

    /** Callers must establish system recreation first; null bindings never select the latest record. */
    suspend fun readSnapshotForRecovery(
        savedSessionId: String?,
        originalRequestId: String?
    ): HandoffStoreResult<PlaybackSnapshotRecord> =
        transaction { state ->
            val snapshot =
                state.snapshot?.takeIf {
                    if (savedSessionId != null) {
                        it.target.version.sessionId == savedSessionId
                    } else {
                        originalRequestId != null &&
                            (it.target.version.sessionId == originalRequestId || it.target.requestId == originalRequestId)
                    }
                }
                    ?: fail(HandoffStoreFailureKind.SNAPSHOT_MISSING)
            if (wallTime() >= snapshot.expiresAtMs) {
                expire(HandoffStoreFailureKind.SNAPSHOT_EXPIRED, state.copy(snapshot = null))
            }
            snapshot
        }

    /** Authorization is the session's live version, which may be ahead of the saved revision. */
    suspend fun saveSnapshot(
        target: PlaybackSnapshotTarget,
        isCurrent: (PlaybackSessionVersion) -> Boolean
    ): HandoffStoreResult<PlaybackSnapshotRecord> {
        val captured =
            try {
                codec.copy(target)
            } catch (_: Exception) {
                return invalidPayload()
            }
        return transaction { state ->
            val saved = state.snapshot ?: fail(HandoffStoreFailureKind.SNAPSHOT_MISSING)
            if (!isCurrent(captured.version) ||
                captured.version.sessionId != saved.target.version.sessionId ||
                captured.version.sourceRevision < saved.target.version.sourceRevision
            ) {
                fail(HandoffStoreFailureKind.STALE_SESSION)
            }
            val snapshot = snapshot(captured)
            // A new pending request may have arrived while the active source kept playing.
            persist(state.copy(snapshot = snapshot))
            snapshot
        }
    }

    suspend fun clearPending(requestId: String): HandoffStoreResult<Unit> =
        transaction { state ->
            if (state.pending?.requestId == requestId) persist(state.copy(pending = null, verification = null))
        }

    suspend fun clearSnapshot(sessionId: String): HandoffStoreResult<Unit> =
        transaction { state ->
            if (state.snapshot
                    ?.target
                    ?.version
                    ?.sessionId == sessionId
            ) {
                persist(state.copy(snapshot = null))
            }
        }

    suspend fun clearExpired(): HandoffStoreResult<Unit> =
        transaction { state ->
            val now = wallTime()
            val pending = state.pending?.takeIf { now < it.expiresAtMs }
            val snapshot = state.snapshot?.takeIf { now < it.expiresAtMs }
            val cleaned = state.copy(pending = pending, verification = state.verification?.takeIf { pending != null }, snapshot = snapshot)
            if (cleaned != state) persist(cleaned)
        }

    private fun pending(
        state: PlaybackHandoffState,
        requestId: String
    ): PendingPlaybackRequest {
        val pending = state.pending ?: fail(HandoffStoreFailureKind.REQUEST_MISSING)
        if (pending.requestId != requestId) fail(HandoffStoreFailureKind.REQUEST_SUPERSEDED)
        if (wallTime() >= pending.expiresAtMs) {
            expire(HandoffStoreFailureKind.REQUEST_EXPIRED, state.copy(pending = null, verification = null))
        }
        return pending
    }

    private fun verification(
        state: PlaybackHandoffState,
        verificationId: String
    ): PendingPlaybackVerification {
        val verification =
            state.verification?.takeIf { it.verificationId == verificationId }
                ?: fail(HandoffStoreFailureKind.VERIFICATION_MISSING)
        pending(state, verification.requestId)
        return verification
    }

    private fun snapshot(target: PlaybackSnapshotTarget): PlaybackSnapshotRecord {
        if (target.version.sessionId.isBlank() || target.version.sourceRevision < 1 || target.positionMs < 0) {
            fail(HandoffStoreFailureKind.INVALID_PAYLOAD)
        }
        val now = wallTime()
        return PlaybackSnapshotRecord(target, now, now + SNAPSHOT_TTL_MS)
    }

    private suspend fun <T> transaction(block: (PlaybackHandoffState) -> T): HandoffStoreResult<T> =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    HandoffStoreResult.Success(block(readState()))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: StoreOperationException) {
                    HandoffStoreResult.Failure(failure.failure)
                }
            }
        }

    private fun readState(): PlaybackHandoffState {
        val encrypted =
            try {
                storage.read()
            } catch (_: Exception) {
                fail(HandoffStoreFailureKind.READ_FAILED)
            }
                ?: return PlaybackHandoffState()
        val plain =
            try {
                cipher.decrypt(encrypted)
            } catch (_: Exception) {
                null
            }
                ?: corrupt(HandoffStoreFailureKind.SENSITIVE_PAYLOAD_UNAVAILABLE)
        return try {
            codec.decode(plain)
        } catch (failure: HandoffCodecException) {
            corrupt(failure.kind)
        } catch (_: Exception) {
            corrupt(HandoffStoreFailureKind.INVALID_PAYLOAD)
        }
    }

    private fun persist(state: PlaybackHandoffState) {
        if (state.pending == null && state.verification == null && state.snapshot == null) {
            if (!write(null)) fail(HandoffStoreFailureKind.WRITE_FAILED)
            return
        }
        val plain =
            try {
                codec.encode(state)
            } catch (_: Exception) {
                fail(HandoffStoreFailureKind.INVALID_PAYLOAD)
            }
        val encrypted =
            try {
                cipher.encrypt(plain)
            } catch (_: Exception) {
                null
            }
                ?: fail(HandoffStoreFailureKind.ENCRYPTION_FAILED)
        if (!write(encrypted)) fail(HandoffStoreFailureKind.WRITE_FAILED)
    }

    private fun write(value: String?): Boolean =
        try {
            storage.write(value)
        } catch (_: Exception) {
            false
        }

    private fun corrupt(kind: HandoffStoreFailureKind): Nothing =
        throw StoreOperationException(HandoffStoreFailure(kind, cleanupFailed = !write(null)))

    private fun expire(
        kind: HandoffStoreFailureKind,
        cleaned: PlaybackHandoffState
    ): Nothing {
        val cleanupFailed =
            try {
                persist(cleaned)
                false
            } catch (_: StoreOperationException) {
                true
            }
        throw StoreOperationException(HandoffStoreFailure(kind, cleanupFailed))
    }

    private fun fail(kind: HandoffStoreFailureKind): Nothing = throw StoreOperationException(HandoffStoreFailure(kind))

    private fun invalidPayload() = HandoffStoreResult.Failure(HandoffStoreFailure(HandoffStoreFailureKind.INVALID_PAYLOAD))

    private class StoreOperationException(
        val failure: HandoffStoreFailure
    ) : Exception(failure.kind.name)

    companion object {
        const val PENDING_TTL_MS = 15 * 60 * 1000L
        const val SNAPSHOT_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
