package com.xyoye.storage_component.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xyoye.common_component.media3.testing.Media3Dependent
import com.xyoye.data_component.data.media3.DownloadValidationRequestData
import com.xyoye.data_component.data.media3.DownloadValidationResponseData
import com.xyoye.data_component.entity.media3.DownloadRequiredAction
import com.xyoye.storage_component.download.validator.DownloadValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@Media3Dependent("Download validator must enforce Media3 compatibility gates")
@RunWith(AndroidJUnit4::class)
class Media3DownloadValidationTest {

    @Test
    fun validatorAllowsAudioOnlyFallback_whenBackendRequestsIt() = runBlocking {
        val gateway = FakeGateway(
            DownloadValidationResponseData(
                downloadId = "dl-audio",
                isCompatible = false,
                requiredAction = DownloadRequiredAction.AUDIO_ONLY_FALLBACK,
                verificationLogs = listOf("Missing h265 decoder")
            )
        )
        val validator = DownloadValidator(
            media3Version = "1.8.0-test",
            validateCall = gateway::invoke
        )

        val outcome = validator.validate("dl-audio", "media-audio", lastVerifiedAt = 0L)

        assertTrue(outcome is DownloadValidator.ValidationOutcome.AllowPlayback)
        val allow = outcome as DownloadValidator.ValidationOutcome.AllowPlayback
        assertTrue(allow.audioOnly)
        assertEquals("Missing h265 decoder", allow.message)
    }

    @Test
    fun validatorBlocksPlayback_whenRedownloadRequired() = runBlocking {
        val gateway = FakeGateway(
            DownloadValidationResponseData(
                downloadId = "dl-redownload",
                isCompatible = false,
                requiredAction = DownloadRequiredAction.REDOWNLOAD,
                verificationLogs = listOf("File checksum mismatch")
            )
        )
        val validator = DownloadValidator(
            media3Version = "1.8.0-test",
            validateCall = gateway::invoke
        )

        val outcome = validator.validate("dl-redownload", "media-redownload", lastVerifiedAt = null)

        assertTrue(outcome is DownloadValidator.ValidationOutcome.Blocked)
        val blocked = outcome as DownloadValidator.ValidationOutcome.Blocked
        assertEquals("File checksum mismatch", blocked.reason)
    }

    private class FakeGateway(
        private val response: DownloadValidationResponseData
    ) {
        suspend fun invoke(request: DownloadValidationRequestData): Result<DownloadValidationResponseData> {
            return Result.success(
                response.copy(
                    downloadId = request.downloadId
                )
            )
        }
    }
}
