package com.xyoye.common_component.playback.handoff

import com.xyoye.data_component.media3.entity.DownloadRequiredAction
import com.xyoye.data_component.media3.entity.Media3Capability
import com.xyoye.data_component.media3.entity.Media3SourceType

data class PlaybackLaunchOptions(
    val mediaId: String,
    val sourceType: Media3SourceType,
    val requestedCapabilities: List<Media3Capability> = emptyList(),
    val autoplay: Boolean = true
) {
    override fun toString(): String = "PlaybackLaunchOptions(sourceType=$sourceType, autoplay=$autoplay)"
}

enum class PlaybackRequestOrigin {
    STORAGE_FILE,
    PLAY_HISTORY,
    ANIME_EPISODE,
    EXTERNAL_INTENT,
    SCREENCAST
}

data class PlaybackRequestInput(
    val descriptor: PlaybackDescriptor,
    val launchOptions: PlaybackLaunchOptions?,
    val origin: PlaybackRequestOrigin
)

sealed interface PlaybackSubmissionResult {
    data class Accepted(
        val requestId: String
    ) : PlaybackSubmissionResult

    data class Rejected(
        val failure: PlaybackPreparationFailure
    ) : PlaybackSubmissionResult
}

sealed interface PlaybackPreparationFailure {
    object RequestMissing : PlaybackPreparationFailure

    object RequestExpired : PlaybackPreparationFailure

    object RequestSuperseded : PlaybackPreparationFailure

    object SessionSnapshotMissing : PlaybackPreparationFailure

    object SensitivePayloadUnavailable : PlaybackPreparationFailure

    object RecoveryStatePersistenceFailed : PlaybackPreparationFailure

    object LibraryMissing : PlaybackPreparationFailure

    object FileMissing : PlaybackPreparationFailure

    object PermissionDenied : PlaybackPreparationFailure

    object CredentialUnavailable : PlaybackPreparationFailure

    object AuthenticationRequired : PlaybackPreparationFailure

    object NetworkUnavailable : PlaybackPreparationFailure

    object UnsupportedSource : PlaybackPreparationFailure

    object InvalidDescriptor : PlaybackPreparationFailure

    data class OfflineValidationBlocked(
        val reason: DownloadRequiredAction
    ) : PlaybackPreparationFailure

    /** The voucher and registration token remain behind the provider boundary. */
    data class InteractiveVerificationRequired(
        val verificationId: String
    ) : PlaybackPreparationFailure

    data class Unknown(
        val causeFingerprint: String
    ) : PlaybackPreparationFailure
}

sealed interface PlaybackOperationResult {
    object Success : PlaybackOperationResult

    data class Rejected(
        val failure: PlaybackPreparationFailure
    ) : PlaybackOperationResult
}

/** Transient data for the existing Geetest UI. Never put this object in navigation or saved state. */
data class PlaybackVerificationChallenge(
    val gt: String,
    val challenge: String
) {
    override fun toString(): String = "PlaybackVerificationChallenge"
}

data class PlaybackVerificationAnswer(
    val challenge: String?,
    val validate: String,
    val seccode: String
) {
    override fun toString(): String = "PlaybackVerificationAnswer"
}

sealed interface PlaybackVerificationResult {
    data class Ready(
        val challenge: PlaybackVerificationChallenge
    ) : PlaybackVerificationResult

    data class Rejected(
        val failure: PlaybackPreparationFailure
    ) : PlaybackVerificationResult
}
