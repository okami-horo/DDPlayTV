package com.xyoye.common_component.source.handoff

import com.xyoye.common_component.media3.Media3LocalStore
import com.xyoye.common_component.network.repository.Media3Repository
import com.xyoye.core_system_component.BuildConfig
import com.xyoye.data_component.media3.dto.DownloadValidationRequestData
import com.xyoye.data_component.media3.dto.DownloadValidationResponseData
import com.xyoye.data_component.media3.entity.DownloadAssetCheck
import com.xyoye.data_component.media3.entity.DownloadRequiredAction
import kotlinx.coroutines.CancellationException

class DownloadValidator(
    private val media3Version: String = BuildConfig.MEDIA3_VERSION,
    private val validateCall: suspend (DownloadValidationRequestData) -> Result<DownloadValidationResponseData> = { request ->
        Media3Repository.validateDownload(request)
    },
    private val onCheckPersisted: suspend (DownloadAssetCheck) -> Unit = { check ->
        Media3LocalStore.upsertDownloadCheck(check)
    }
) {
    suspend fun validate(
        downloadId: String,
        mediaId: String,
        lastVerifiedAt: Long?
    ): ValidationOutcome {
        val request =
            DownloadValidationRequestData(
                downloadId = downloadId,
                mediaId = mediaId,
                media3Version = media3Version,
                lastVerifiedAt = lastVerifiedAt
            )
        val response =
            validateCall(request).getOrElse {
                if (it is CancellationException) throw it
                return ValidationOutcome.Blocked(it.message ?: "Download validation failed")
            }
        val history =
            DownloadAssetCheck(
                downloadId = response.downloadId,
                mediaId = request.mediaId,
                lastVerifiedAt = System.currentTimeMillis(),
                isCompatible = response.isCompatible,
                requiredAction = response.requiredAction,
                verificationLogs = response.verificationLogs
            )
        onCheckPersisted(history)
        return when (response.requiredAction) {
            DownloadRequiredAction.NONE ->
                ValidationOutcome.AllowPlayback(
                    audioOnly = false,
                    message = response.verificationLogs.firstOrNull()
                )

            DownloadRequiredAction.AUDIO_ONLY_FALLBACK ->
                ValidationOutcome.AllowPlayback(
                    audioOnly = true,
                    message = response.verificationLogs.firstOrNull()
                )

            DownloadRequiredAction.REVALIDATE,
            DownloadRequiredAction.REDOWNLOAD ->
                ValidationOutcome.Blocked(
                    response.verificationLogs.firstOrNull()
                        ?: "Download requires additional validation",
                    response.requiredAction
                )
        }
    }

    sealed class ValidationOutcome {
        data class AllowPlayback(
            val audioOnly: Boolean,
            val message: String?
        ) : ValidationOutcome()

        data class Blocked(
            val reason: String,
            val requiredAction: DownloadRequiredAction = DownloadRequiredAction.REVALIDATE
        ) : ValidationOutcome()
    }
}
