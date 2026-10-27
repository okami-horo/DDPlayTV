package com.xyoye.common_component.network.repository

import com.xyoye.data_component.media3.dto.DownloadValidationRequestData
import com.xyoye.data_component.media3.dto.DownloadValidationResponseData
import com.xyoye.data_component.media3.entity.DownloadRequiredAction
import com.xyoye.data_component.media3.entity.TelemetryEvent

/** Remote gateway remains disabled. Playback identity and capabilities belong to the local player. */
object Media3Repository : BaseRepository() {
    suspend fun emitTelemetry(event: TelemetryEvent): Result<Unit> = Result.success(Unit)

    suspend fun validateDownload(request: DownloadValidationRequestData): Result<DownloadValidationResponseData> =
        Result.success(
            DownloadValidationResponseData(
                downloadId = request.downloadId,
                isCompatible = true,
                requiredAction = DownloadRequiredAction.NONE,
                verificationLogs = emptyList()
            )
        )
}
