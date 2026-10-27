package com.xyoye.player_component.playback.handoff

import com.xyoye.common_component.playback.handoff.ExternalUriDescriptor
import com.xyoye.common_component.playback.handoff.PlayHistoryDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackCastVideo
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.playback.handoff.PlaybackLaunchOptions
import com.xyoye.common_component.playback.handoff.PlaybackQueueContext
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.playback.handoff.ScreencastDescriptor
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.common_component.playback.handoff.TorrentDescriptor
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.StorageSort
import com.xyoye.data_component.media3.entity.Media3SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class PlaybackHandoffStoreTest {
    private val blob = MemoryBlob()
    private val cipher = TestCipher()
    private var now = 1000L
    private var sequence = 0
    private val codec = PlaybackHandoffCodec()
    private val store = PlaybackHandoffStore(blob, cipher, { now }, { "id-${++sequence}" }, Dispatchers.Unconfined)
    private val version = PlaybackSessionVersion("session", 1)
    private val input get() =
        PlaybackRequestInput(
            ExternalUriDescriptor(
                "https://example.test/private.mkv?token=secret-query",
                Media3SourceType.STREAM,
                headers = mapOf("Cookie" to "private-cookie", "Authorization" to "private-header")
            ),
            null,
            PlaybackRequestOrigin.EXTERNAL_INTENT
        )

    @Test fun latestRequestWinsAndConditionalActivationConsumesOnlyOnce() =
        runBlocking {
            val first = value(store.submit(input))
            val second = value(store.submit(input))
            assertEquals(HandoffStoreFailureKind.REQUEST_SUPERSEDED, error(store.readPending(first.requestId)).kind)
            assertEquals(second.requestId, value(store.readPending(second.requestId)).requestId)
            assertEquals(
                HandoffStoreFailureKind.REQUEST_SUPERSEDED,
                error(store.activate(first.requestId, target(first.requestId)) { true }).kind
            )
            value(store.activate(second.requestId, target(second.requestId)) { true })
            assertEquals(
                HandoffStoreFailureKind.REQUEST_MISSING,
                error(store.activate(second.requestId, target(second.requestId)) { true }).kind
            )
            assertEquals(second.requestId, value(store.readSnapshot(version.sessionId)).target.requestId)
        }

    @Test fun verificationReadsAndUpdatesNeverExtendPendingExpiry() =
        runBlocking {
            val pending = value(store.submit(input))
            assertEquals(pending.createdAtMs + 15 * 60 * 1000L, pending.expiresAtMs)
            val verification = value(store.startVerification(pending.requestId, PlaybackVerificationPayload("private-voucher")))
            now = pending.expiresAtMs - 1
            value(store.readVerification(verification.verificationId))
            value(store.updateVerification(verification.verificationId, verification.payload.copy(registerToken = "private-register")))
            assertEquals(pending.expiresAtMs, value(store.readPending(pending.requestId)).expiresAtMs)
            now++
            assertEquals(HandoffStoreFailureKind.REQUEST_EXPIRED, error(store.readVerification(verification.verificationId)).kind)
            assertNull(blob.data)
        }

    @Test fun expiredPreparationCannotCommitAndFailedCleanupIsReported() =
        runBlocking {
            val pending = value(store.submit(input))
            now = pending.expiresAtMs
            blob.failWrite = true
            val failure = error(store.activate(pending.requestId, target(pending.requestId)) { true })
            assertEquals(HandoffStoreFailureKind.REQUEST_EXPIRED, failure.kind)
            assertTrue(failure.cleanupFailed)
            assertNotNull(blob.data)
            blob.failWrite = false
            assertEquals(HandoffStoreFailureKind.REQUEST_EXPIRED, error(store.readPending(pending.requestId)).kind)
            assertNull(blob.data)
        }

    @Test fun snapshotWritesPreserveNewPendingAndAllowRuntimeAheadOfSavedRevision() =
        runBlocking {
            val first = value(store.submit(input))
            value(store.activate(first.requestId, target(first.requestId)) { true })
            val pending = value(store.submit(input))
            val runtime = version.copy(sourceRevision = 3)
            blob.failWrite = true
            error(store.saveSnapshot(target(first.requestId, runtime)) { it == runtime })
            assertEquals(1L, value(store.readSnapshot(version.sessionId)).target.version.sourceRevision)
            blob.failWrite = false
            value(store.saveSnapshot(target(first.requestId, runtime)) { it == runtime })
            assertEquals(3L, value(store.readSnapshot(version.sessionId)).target.version.sourceRevision)
            assertEquals(pending.requestId, value(store.readPending(pending.requestId)).requestId)
            assertEquals(HandoffStoreFailureKind.STALE_SESSION, error(store.saveSnapshot(target(first.requestId)) { it == runtime }).kind)
        }

    @Test fun failedEncryptionOrWritePreservesOldSnapshotAndPending() =
        runBlocking {
            val first = value(store.submit(input))
            value(store.activate(first.requestId, target(first.requestId)) { true })
            val replacement = value(store.submit(input))
            val previous = blob.data
            cipher.failEncrypt = true
            assertEquals(
                HandoffStoreFailureKind.ENCRYPTION_FAILED,
                error(
                    store.activate(replacement.requestId, target(replacement.requestId)) {
                        true
                    }
                ).kind
            )
            assertEquals(previous, blob.data)
            cipher.failEncrypt = false
            blob.failWrite = true
            assertEquals(
                HandoffStoreFailureKind.WRITE_FAILED,
                error(
                    store.activate(replacement.requestId, target(replacement.requestId)) {
                        true
                    }
                ).kind
            )
            assertEquals(previous, blob.data)
            assertEquals(first.requestId, value(store.readSnapshot(version.sessionId)).target.requestId)
        }

    @Test fun idScopedCleanupCannotRemoveNewRequestOrDifferentSession() =
        runBlocking {
            val active = value(store.submit(input))
            value(store.activate(active.requestId, target(active.requestId)) { true })
            val pending = value(store.submit(input))
            value(store.clearPending(active.requestId))
            value(store.clearSnapshot("other-session"))
            assertEquals(pending.requestId, value(store.readPending(pending.requestId)).requestId)
            value(store.clearSnapshot(version.sessionId))
            assertEquals(HandoffStoreFailureKind.SNAPSHOT_MISSING, error(store.readSnapshot(version.sessionId)).kind)
            assertEquals(pending.requestId, value(store.readPending(pending.requestId)).requestId)
        }

    @Test fun unsupportedSchemaAndDecryptionFailureDeleteOnlyWhenStorageAcceptsCleanup() =
        runBlocking {
            blob.data = cipher.encrypt("{\"schemaVersion\":99}")
            blob.failWrite = true
            val unsupported = error(store.submit(input))
            assertEquals(HandoffStoreFailureKind.UNSUPPORTED_SCHEMA, unsupported.kind)
            assertTrue(unsupported.cleanupFailed)
            blob.failWrite = false
            error(store.submit(input))
            assertNull(blob.data)
            blob.data = "unencrypted-private-input"
            assertEquals(HandoffStoreFailureKind.SENSITIVE_PAYLOAD_UNAVAILABLE, error(store.submit(input)).kind)
            assertNull(blob.data)
        }

    @Test fun failedSnapshotSaveDoesNotRefreshExpiryAndExpiredSnapshotPreservesNewPending() =
        runBlocking {
            val first = value(store.submit(input))
            val saved = value(store.activate(first.requestId, target(first.requestId)) { true })
            assertEquals(saved.savedAtMs + 24 * 60 * 60 * 1000L, saved.expiresAtMs)
            now = saved.expiresAtMs - 1
            blob.failWrite = true
            error(store.saveSnapshot(target(first.requestId)) { true })
            assertEquals(saved.expiresAtMs, value(store.readSnapshot(version.sessionId)).expiresAtMs)
            blob.failWrite = false
            val pending = value(store.submit(input))
            now++
            assertEquals(HandoffStoreFailureKind.SNAPSHOT_EXPIRED, error(store.readSnapshot(version.sessionId)).kind)
            assertEquals(pending.requestId, value(store.readPending(pending.requestId)).requestId)
        }

    @Test fun concurrentSubmissionsAndCommitLeaveAConsistentEncryptedBlob() =
        runBlocking {
            val pending = value(store.submit(input))
            val results =
                listOf(
                    async(Dispatchers.Default) { store.activate(pending.requestId, target(pending.requestId)) { true } },
                    async(Dispatchers.Default) { store.submit(input) }
                ).awaitAll()
            assertTrue(results[1] is HandoffStoreResult.Success)
            val state = codec.decode(cipher.decrypt(blob.data!!)!!)
            assertNotNull(state.pending)
            assertNotEquals(pending.requestId, state.pending!!.requestId)
            if (results[0] is HandoffStoreResult.Success) {
                assertEquals(pending.requestId, state.snapshot!!.target.requestId)
            } else {
                assertNull(state.snapshot)
            }
        }

    @Test fun submissionDetachesInputsAndCiphertextAndDiagnosticsDoNotExposeSecrets() =
        runBlocking {
            val headers = mutableMapOf("Authorization" to "private-header")
            val mutableInput = input.copy(descriptor = (input.descriptor as ExternalUriDescriptor).copy(headers = headers))
            val pending = value(store.submit(mutableInput))
            assertFalse(blob.data!!.contains("private-header"))
            assertFalse(blob.data!!.contains("secret-query"))
            headers.clear()
            val verification =
                value(store.startVerification(pending.requestId, PlaybackVerificationPayload("private-voucher", "private-register")))
            val read = value(store.readPending(pending.requestId))
            assertEquals("private-header", (read.input.descriptor as ExternalUriDescriptor).headers["Authorization"])
            val diagnostics = listOf(pending, read, verification).joinToString()
            for (secret in listOf("private-header", "private-voucher", "private-register", "secret-query", "private.mkv")) {
                assertFalse(blob.data!!.contains(secret))
                assertFalse(diagnostics.contains(secret))
            }
            blob.failRead = true
            assertEquals(HandoffStoreFailureKind.READ_FAILED, error(store.readPending(pending.requestId)).kind)
        }

    @Test fun allDescriptorFamiliesAndLaunchOptionsRoundTripWithoutPlaintextStorage() =
        runBlocking {
            val reference = PlaybackFileReference("stable", "/private/library/video.mkv", displayTitle = "Episode")
            val descriptors =
                listOf(
                    StoredLibraryFileDescriptor(
                        8,
                        MediaType.SMB_SERVER,
                        reference,
                        PlaybackQueueContext(null, StorageSort.SIZE, true, "stable", listOf(reference), false)
                    ),
                    PlayHistoryDescriptor(4, "stable", 8),
                    TorrentDescriptor("magnet:?xt=urn:btih:private-magnet", 3, "download", "stable", "episode four.mkv"),
                    ExternalUriDescriptor("file:///private/local/video.mkv", Media3SourceType.STREAM),
                    ScreencastDescriptor(
                        "192.0.2.2",
                        8080,
                        listOf(PlaybackCastVideo("title", "stable", positionMs = 123)),
                        "stable",
                        mapOf("Cookie" to "private-cookie")
                    )
                )
            val options = PlaybackLaunchOptions("media", Media3SourceType.STREAM, autoplay = false)
            for (descriptor in descriptors) {
                val request = input.copy(descriptor = descriptor, launchOptions = options)
                val pending = value(store.submit(request))
                assertEquals(request, value(store.readPending(pending.requestId)).input)
                assertFalse(blob.data!!.contains("private"))
                assertFalse(blob.data!!.contains("magnet:"))
                assertFalse(blob.data!!.contains("file:"))
            }
        }

    @Test fun replacementMakesLateVerificationResultInvalidAndLeavesNewPendingIntact() =
        runBlocking {
            val old = value(store.submit(input))
            val verification = value(store.startVerification(old.requestId, PlaybackVerificationPayload("voucher")))
            val next = value(store.submit(input))
            assertEquals(
                HandoffStoreFailureKind.VERIFICATION_MISSING,
                error(store.updateVerification(verification.verificationId, verification.payload.copy(validated = true))).kind
            )
            assertEquals(next.requestId, value(store.readPending(next.requestId)).requestId)
        }

    @Test
    fun staleActivationCannotConsumePendingOrAdvanceSnapshot() =
        runBlocking {
            val active = value(store.submit(input))
            value(store.activate(active.requestId, target(active.requestId)) { true })
            val pending = value(store.submit(input))
            val before = blob.data
            val failure = error(store.activate(pending.requestId, target(pending.requestId, version.copy(sourceRevision = 2))) { false })
            assertEquals(HandoffStoreFailureKind.STALE_SESSION, failure.kind)
            assertEquals(before, blob.data)
            assertEquals(active.requestId, value(store.readSnapshot(version.sessionId)).target.requestId)
            assertEquals(pending.requestId, value(store.readPending(pending.requestId)).requestId)
        }

    @Test fun malformedEncryptedPayloadAndMissingRecordHaveDifferentResults() =
        runBlocking {
            assertEquals(HandoffStoreFailureKind.REQUEST_MISSING, error(store.readPending("missing")).kind)
            blob.data = cipher.encrypt("{\"schemaVersion\":1,\"pending\":{\"requestId\":\"truncated\"}}")
            assertEquals(HandoffStoreFailureKind.INVALID_PAYLOAD, error(store.readPending("truncated")).kind)
            assertNull(blob.data)
        }

    private fun target(
        requestId: String,
        revision: PlaybackSessionVersion = version
    ) = PlaybackSnapshotTarget(
        revision,
        requestId,
        input.descriptor,
        null,
        positionMs = 100,
        playWhenReady = true
    )

    private fun <T> value(result: HandoffStoreResult<T>): T = (result as HandoffStoreResult.Success).value

    private fun error(result: HandoffStoreResult<*>): HandoffStoreFailure = (result as HandoffStoreResult.Failure).failure

    private class MemoryBlob : HandoffBlobStorage {
        var data: String? = null
        var failRead = false
        var failWrite = false

        override fun read(): String? {
            if (failRead) error("private read detail")
            return data
        }

        override fun write(value: String?): Boolean {
            if (failWrite) return false
            data = value
            return true
        }
    }

    private class TestCipher : HandoffCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        var failEncrypt = false

        override fun encrypt(plain: String): String? {
            if (failEncrypt) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
        }

        override fun decrypt(encrypted: String): String? =
            runCatching {
                val bytes = Base64.getDecoder().decode(encrypted)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
            }.getOrNull()
    }
}
