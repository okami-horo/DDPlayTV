package com.xyoye.common_component.source.handoff

import com.xyoye.data_component.media3.dto.DownloadValidationRequestData
import com.xyoye.data_component.media3.dto.DownloadValidationResponseData
import com.xyoye.data_component.media3.entity.DownloadAssetCheck
import com.xyoye.data_component.media3.entity.DownloadRequiredAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadValidatorTest {
    @Test
    fun cancellationResultIsNotConvertedToBlockedPlayback() =
        runBlocking {
            val validator =
                DownloadValidator(
                    media3Version = "1.0.0",
                    validateCall = { Result.failure(kotlinx.coroutines.CancellationException()) },
                    onCheckPersisted = { error("Cancelled checks must not persist") }
                )
            try {
                validator.validate("download", "media", null)
                org.junit.Assert.fail("Cancellation was swallowed")
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }

    private val noOpPersist: suspend (DownloadAssetCheck) -> Unit = {}

    private fun validatorWith(
        action: DownloadRequiredAction,
        logs: List<String> = emptyList()
    ): DownloadValidator {
        val response =
            DownloadValidationResponseData(
                downloadId = "dl-001",
                isCompatible = action == DownloadRequiredAction.NONE || action == DownloadRequiredAction.AUDIO_ONLY_FALLBACK,
                requiredAction = action,
                verificationLogs = logs
            )
        return DownloadValidator(
            media3Version = "1.0.0",
            validateCall = { Result.success(response) },
            onCheckPersisted = noOpPersist
        )
    }

    @Test
    fun noneActionAllowsFullPlayback() =
        runBlocking {
            val outcome = validatorWith(DownloadRequiredAction.NONE).validate("dl-001", "media-1", null)

            assertTrue(outcome is DownloadValidator.ValidationOutcome.AllowPlayback)
            val allow = outcome as DownloadValidator.ValidationOutcome.AllowPlayback
            assertFalse(allow.audioOnly)
        }

    @Test
    fun audioOnlyFallbackAllowsAudioOnlyPlayback() =
        runBlocking {
            val outcome =
                validatorWith(DownloadRequiredAction.AUDIO_ONLY_FALLBACK)
                    .validate("dl-001", "media-1", null)

            assertTrue(outcome is DownloadValidator.ValidationOutcome.AllowPlayback)
            val allow = outcome as DownloadValidator.ValidationOutcome.AllowPlayback
            assertTrue(allow.audioOnly)
        }

    @Test
    fun revalidateActionBlocks() =
        runBlocking {
            val outcome =
                validatorWith(DownloadRequiredAction.REVALIDATE, listOf("needs check"))
                    .validate("dl-001", "media-1", null)

            assertTrue(outcome is DownloadValidator.ValidationOutcome.Blocked)
            assertEquals("needs check", (outcome as DownloadValidator.ValidationOutcome.Blocked).reason)
        }

    @Test
    fun redownloadActionBlocks() =
        runBlocking {
            val outcome =
                validatorWith(DownloadRequiredAction.REDOWNLOAD)
                    .validate("dl-001", "media-1", null)

            assertTrue(outcome is DownloadValidator.ValidationOutcome.Blocked)
        }

    @Test
    fun blockedReasonUsesFirstLogWhenAvailable() =
        runBlocking {
            val outcome =
                validatorWith(DownloadRequiredAction.REDOWNLOAD, listOf("redownload reason", "extra"))
                    .validate("dl-001", "media-1", null)

            val blocked = outcome as DownloadValidator.ValidationOutcome.Blocked
            assertEquals("redownload reason", blocked.reason)
        }

    @Test
    fun blockedReasonFallbackWhenNoLogs() =
        runBlocking {
            val outcome =
                validatorWith(DownloadRequiredAction.REDOWNLOAD, emptyList())
                    .validate("dl-001", "media-1", null)

            val blocked = outcome as DownloadValidator.ValidationOutcome.Blocked
            assertTrue(blocked.reason.isNotBlank())
        }

    @Test
    fun noneActionMessageFromFirstLog() =
        runBlocking {
            val outcome =
                validatorWith(DownloadRequiredAction.NONE, listOf("all good"))
                    .validate("dl-001", "media-1", null)

            val allow = outcome as DownloadValidator.ValidationOutcome.AllowPlayback
            assertEquals("all good", allow.message)
        }

    @Test
    fun noneActionMessageIsNullWhenNoLogs() =
        runBlocking {
            val outcome =
                validatorWith(DownloadRequiredAction.NONE, emptyList())
                    .validate("dl-001", "media-1", null)

            val allow = outcome as DownloadValidator.ValidationOutcome.AllowPlayback
            assertNull(allow.message)
        }

    @Test
    fun validateCallFailureReturnsBlocked() =
        runBlocking {
            val validator =
                DownloadValidator(
                    media3Version = "1.0.0",
                    validateCall = { Result.failure(RuntimeException("network error")) },
                    onCheckPersisted = noOpPersist
                )
            val outcome = validator.validate("dl-001", "media-1", null)

            assertTrue(outcome is DownloadValidator.ValidationOutcome.Blocked)
            val blocked = outcome as DownloadValidator.ValidationOutcome.Blocked
            assertEquals("network error", blocked.reason)
        }

    @Test
    fun validateCallFailureWithNoMessageReturnsDefaultReason() =
        runBlocking {
            val validator =
                DownloadValidator(
                    media3Version = "1.0.0",
                    validateCall = { Result.failure(RuntimeException()) },
                    onCheckPersisted = noOpPersist
                )
            val outcome = validator.validate("dl-001", "media-1", null)

            assertTrue(outcome is DownloadValidator.ValidationOutcome.Blocked)
            assertTrue((outcome as DownloadValidator.ValidationOutcome.Blocked).reason.isNotBlank())
        }

    @Test
    fun persistCallbackInvokedOnSuccess() =
        runBlocking {
            val persisted = mutableListOf<DownloadAssetCheck>()
            val response =
                DownloadValidationResponseData(
                    downloadId = "dl-001",
                    isCompatible = true,
                    requiredAction = DownloadRequiredAction.NONE
                )
            val validator =
                DownloadValidator(
                    media3Version = "2.0.0",
                    validateCall = { Result.success(response) },
                    onCheckPersisted = { persisted.add(it) }
                )

            validator.validate("dl-001", "media-1", null)

            assertEquals(1, persisted.size)
            assertEquals("dl-001", persisted[0].downloadId)
            assertEquals("media-1", persisted[0].mediaId)
        }

    @Test
    fun persistCallbackNotInvokedOnValidateFailure() =
        runBlocking {
            val persisted = mutableListOf<DownloadAssetCheck>()
            val validator =
                DownloadValidator(
                    media3Version = "1.0.0",
                    validateCall = { Result.failure(RuntimeException("fail")) },
                    onCheckPersisted = { persisted.add(it) }
                )

            validator.validate("dl-001", "media-1", null)

            assertTrue(persisted.isEmpty())
        }

    @Test
    fun requestUsesProvidedMediaAndDownloadIds() =
        runBlocking {
            var capturedRequest: DownloadValidationRequestData? = null
            val validator =
                DownloadValidator(
                    media3Version = "1.5.0",
                    validateCall = { req ->
                        capturedRequest = req
                        Result.success(
                            DownloadValidationResponseData(
                                downloadId = req.downloadId,
                                isCompatible = true,
                                requiredAction = DownloadRequiredAction.NONE
                            )
                        )
                    },
                    onCheckPersisted = noOpPersist
                )

            validator.validate("my-download", "my-media", lastVerifiedAt = 1000L)

            assertEquals("my-download", capturedRequest?.downloadId)
            assertEquals("my-media", capturedRequest?.mediaId)
            assertEquals("1.5.0", capturedRequest?.media3Version)
            assertEquals(1000L, capturedRequest?.lastVerifiedAt)
        }
}
