package com.xyoye.common_component.network.service

import com.xyoye.data_component.media3.dto.DownloadValidationRequestData
import com.xyoye.data_component.media3.dto.DownloadValidationResponseData
import com.xyoye.data_component.media3.entity.TelemetryEvent
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface Media3Service {
    @POST("v1/media3/telemetry")
    suspend fun emitTelemetry(
        @Body event: TelemetryEvent
    ): Response<Unit>

    @POST("v1/media3/downloads/validate")
    suspend fun validateDownload(
        @Body request: DownloadValidationRequestData
    ): DownloadValidationResponseData
}
