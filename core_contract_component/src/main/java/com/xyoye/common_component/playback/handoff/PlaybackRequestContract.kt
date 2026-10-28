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
