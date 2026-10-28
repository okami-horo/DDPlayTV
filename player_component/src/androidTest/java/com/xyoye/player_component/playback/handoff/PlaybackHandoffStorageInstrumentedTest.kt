package com.xyoye.player_component.playback.handoff

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.playback.handoff.ExternalUriDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.data_component.media3.entity.Media3SourceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackHandoffStorageInstrumentedTest {
    private val storage = MmkvHandoffStorage()
    private var now = 1000L
    private val input =
        PlaybackRequestInput(
            ExternalUriDescriptor(
                "https://example.test/video?token=secret",
                Media3SourceType.STREAM,
                headers = mapOf("Authorization" to "private-header")
            ),
            null,
            PlaybackRequestOrigin.EXTERNAL_INTENT
        )

    @Before fun initialize() {
        MMKV.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(storage.write(null))
    }

    @After fun clear() {
        assertTrue(storage.write(null))
    }

    @Test fun realKeystoreAndMmkvRestoreOnlyBoundSnapshotAndClearOnExit() =
        runBlocking {
            val store = PlaybackHandoffStore(wallTime = { now })
            val pending = value(store.submit(input))
            val encrypted = requireNotNull(storage.read())
            assertTrue(encrypted.startsWith("ddenc1:"))
            assertFalse(encrypted.contains("secret"))
            assertFalse(encrypted.contains("private-header"))
            val version = PlaybackSessionVersion(pending.requestId, 1)
            val target = PlaybackSnapshotTarget(version, pending.requestId, input.descriptor, null, 9000, true)
            value(store.activate(pending.requestId, target) { true })
            val recreatedStore = PlaybackHandoffStore(wallTime = { now })
            assertEquals(target, value(recreatedStore.readSnapshot(version.sessionId)).target)
            assertTrue(recreatedStore.readSnapshotForRecovery(null, null) is HandoffStoreResult.Failure)
            value(recreatedStore.clearSnapshot(version.sessionId))
            assertNull(storage.read())
            assertTrue(recreatedStore.readSnapshot(version.sessionId) is HandoffStoreResult.Failure)
        }

    @Test fun fixedExpirySurvivesRealEncryptionAndPendingReads() =
        runBlocking {
            val store = PlaybackHandoffStore(wallTime = { now })
            val pending = value(store.submit(input))
            now = pending.expiresAtMs - 1
            assertEquals(pending.expiresAtMs, value(store.readPending(pending.requestId)).expiresAtMs)
            now++
            val failure = store.readPending(pending.requestId) as HandoffStoreResult.Failure
            assertEquals(HandoffStoreFailureKind.REQUEST_EXPIRED, failure.failure.kind)
            assertNull(storage.read())
        }

    @Test fun corruptCiphertextFailsClosedWithoutLeakingPayloadAndNextRequestRecovers() =
        runBlocking {
            val store = PlaybackHandoffStore(wallTime = { now })
            val pending = value(store.submit(input))
            val encrypted = requireNotNull(storage.read())
            assertTrue(storage.write(encrypted.dropLast(8) + "corrupt!"))
            val failure = store.readPending(pending.requestId) as HandoffStoreResult.Failure
            assertEquals(HandoffStoreFailureKind.SENSITIVE_PAYLOAD_UNAVAILABLE, failure.failure.kind)
            assertFalse(failure.toString().contains("secret"))
            assertFalse(failure.toString().contains("corrupt"))
            assertNull(storage.read())
            assertTrue(store.readPending(pending.requestId) is HandoffStoreResult.Failure)
            assertNotEquals(pending.requestId, value(store.submit(input)).requestId)
        }

    private fun <T> value(result: HandoffStoreResult<T>): T = (result as HandoffStoreResult.Success).value
}
