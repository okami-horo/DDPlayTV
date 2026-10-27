package com.xyoye.common_component.storage.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.common_component.storage.impl.TorrentStorage
import com.xyoye.common_component.utils.thunder.ThunderManager
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Pass leaseTorrentPath to run the real bundled AAR; absent fixtures are skipped, never passed. */
@RunWith(AndroidJUnit4::class)
class ThunderLeaseCoexistenceTest {
    @Test
    fun sameTorrentTasksRemainIndependentWhenEitherLeaseRetires() =
        runBlocking {
            val fixture = InstrumentationRegistry.getArguments().getString("leaseTorrentPath")
            assumeTrue("Requires an explicit local torrent fixture", !fixture.isNullOrBlank())
            assertTrue("Torrent fixture must be readable", File(fixture!!).canRead())
            assertTrue("Native Thunder must initialize on this device", ThunderManager.ensureInitialized())
            val manager = ThunderManager.getInstance()
            val profile = PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.GLOBAL)
            for (retireFirst in listOf(true, false)) {
                val storage =
                    TorrentStorage(MediaLibraryEntity(displayName = "lease fixture", url = fixture, mediaType = MediaType.MAGNET_LINK))
                val leases = mutableListOf<PlaybackTransportLease>()
                try {
                    val file = storage.openDirectory(storage.getRootFile(), true).first { it.isVideoFile() }

                    suspend fun acquire(): PlaybackTransportLease {
                        val result = PlaybackTransportFactory().acquire(storage, file, profile)
                        if (result is TransportAcquisitionResult.Failure && result.cause != null) {
                            throw AssertionError("Native acquisition failed: ${result.reason}", result.cause)
                        }
                        assertTrue("Native acquisition failed: $result", result is TransportAcquisitionResult.Success)
                        return (result as TransportAcquisitionResult.Success).transport.lease.also { leases.add(it) }
                    }
                    val a = acquire()
                    val aId = manager.getTaskIdForPlayUrl(a.playUrl)
                    val b = acquire()
                    val bId = manager.getTaskIdForPlayUrl(b.playUrl)
                    assertTrue(aId >= 0 && bId >= 0)
                    assertNotEquals("The native AAR must allocate independent tasks", aId, bId)
                    assertNotEquals(a.playUrl, b.playUrl)
                    val retired = if (retireFirst) a else b
                    val live = if (retireFirst) b else a
                    val liveId = if (retireFirst) bId else aId
                    val before = manager.getTaskInfo(liveId)
                    assertEquals("Native task must not have an error", 0, before.errorCode)
                    assertTrue(
                        "Task must be running or complete: ${before.taskStatus}",
                        ThunderManager.readTaskInfo(before).statusText in setOf("Running", "Success")
                    )
                    retired.release()
                    retired.release()
                    assertEquals(-1L, manager.getTaskIdForPlayUrl(retired.playUrl))
                    assertEquals(liveId, manager.getTaskIdForPlayUrl(live.playUrl))
                    val after = manager.getTaskInfo(liveId)
                    assertEquals(0, after.errorCode)
                    assertTrue(
                        "Retiring the other task must not stop this one",
                        ThunderManager.readTaskInfo(after).statusText in setOf("Running", "Success")
                    )
                } finally {
                    leases.forEach { it.release() }
                    storage.close()
                }
            }
        }
}
