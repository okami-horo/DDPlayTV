package com.xyoye.player_component.playback.handoff

import com.xyoye.common_component.playback.handoff.PlaybackDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackLaunchOptions
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion

internal data class PendingPlaybackRequest(
    val requestId: String,
    val input: PlaybackRequestInput,
    val createdAtMs: Long,
    val expiresAtMs: Long
) {
    override fun toString(): String = "PendingPlaybackRequest(requestId=$requestId)"
}

internal data class PlaybackVerificationPayload(
    val voucher: String,
    val registerToken: String? = null,
    val gt: String? = null,
    val challenge: String? = null,
    val validated: Boolean = false
) {
    override fun toString(): String = "PlaybackVerificationPayload"
}

internal data class PendingPlaybackVerification(
    val requestId: String,
    val verificationId: String,
    val payload: PlaybackVerificationPayload
) {
    override fun toString(): String = "PendingPlaybackVerification(verificationId=$verificationId)"
}

internal data class PlaybackSnapshotTarget(
    val version: PlaybackSessionVersion,
    val requestId: String,
    val descriptor: PlaybackDescriptor,
    val launchOptions: PlaybackLaunchOptions?,
    val positionMs: Long,
    val playWhenReady: Boolean
) {
    override fun toString(): String = "PlaybackSnapshotTarget(version=$version)"
}

internal data class PlaybackSnapshotRecord(
    val target: PlaybackSnapshotTarget,
    val savedAtMs: Long,
    val expiresAtMs: Long
) {
    override fun toString(): String = "PlaybackSnapshotRecord(version=${target.version})"
}

/** One encrypted value. Native candidates and retry work are deliberately memory-only. */
internal data class PlaybackHandoffState(
    val schemaVersion: Int = 1,
    val pending: PendingPlaybackRequest? = null,
    val verification: PendingPlaybackVerification? = null,
    val snapshot: PlaybackSnapshotRecord? = null
) {
    override fun toString(): String = "PlaybackHandoffState(schemaVersion=$schemaVersion)"
}

internal enum class HandoffStoreFailureKind {
    REQUEST_MISSING,
    REQUEST_SUPERSEDED,
    REQUEST_EXPIRED,
    VERIFICATION_MISSING,
    SNAPSHOT_MISSING,
    SNAPSHOT_EXPIRED,
    STALE_SESSION,
    UNSUPPORTED_SCHEMA,
    INVALID_PAYLOAD,
    SENSITIVE_PAYLOAD_UNAVAILABLE,
    READ_FAILED,
    WRITE_FAILED,
    ENCRYPTION_FAILED
}

internal data class HandoffStoreFailure(
    val kind: HandoffStoreFailureKind,
    val cleanupFailed: Boolean = false
)

internal sealed interface HandoffStoreResult<out T> {
    data class Success<T>(
        val value: T
    ) : HandoffStoreResult<T>

    data class Failure(
        val failure: HandoffStoreFailure
    ) : HandoffStoreResult<Nothing>
}
