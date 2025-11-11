package com.xyoye.storage_component.download.validator

import com.xyoye.common_component.network.repository.Media3Repository
import com.xyoye.data_component.data.media3.DownloadValidationRequestData
import com.xyoye.data_component.data.media3.DownloadValidationResponseData
import com.xyoye.data_component.entity.media3.DownloadRequiredAction
import com.xyoye.storage_component.BuildConfig

class DownloadValidator(
    private val media3Version: String = BuildConfig.MEDIA3_VERSION,
    private val validateCall: suspend (DownloadValidationRequestData) -> Result<DownloadValidationResponseData> = { request ->
        Media3Repository.validateDownload(request)
    }
) {

    suspend fun validate(
        downloadId: String,
        mediaId: String,
        lastVerifiedAt: Long?
    ): ValidationOutcome {
        val request = DownloadValidationRequestData(
            downloadId = downloadId,
            mediaId = mediaId,
            media3Version = media3Version,
            lastVerifiedAt = lastVerifiedAt
        )
        val response = validateCall(request).getOrElse {
            return ValidationOutcome.Blocked(it.message ?: "Download validation failed")
        }
        return when (response.requiredAction) {
            DownloadRequiredAction.NONE -> ValidationOutcome.AllowPlayback(
                audioOnly = false,
                message = response.verificationLogs.firstOrNull()
            )

            DownloadRequiredAction.AUDIO_ONLY_FALLBACK -> ValidationOutcome.AllowPlayback(
                audioOnly = true,
                message = response.verificationLogs.firstOrNull()
            )

            DownloadRequiredAction.REVALIDATE,
            DownloadRequiredAction.REDOWNLOAD -> ValidationOutcome.Blocked(
                response.verificationLogs.firstOrNull()
                    ?: "Download requires additional validation"
            )
        }
    }

    sealed class ValidationOutcome {
        data class AllowPlayback(
            val audioOnly: Boolean,
            val message: String?
        ) : ValidationOutcome()

        data class Blocked(
            val reason: String
        ) : ValidationOutcome()
    }
}
