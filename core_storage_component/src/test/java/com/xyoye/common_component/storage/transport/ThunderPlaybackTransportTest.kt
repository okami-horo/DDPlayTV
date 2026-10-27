package com.xyoye.common_component.storage.transport

import com.xunlei.downloadlib.parameter.BtTaskParam
import com.xyoye.common_component.storage.file.helper.TorrentBean
import com.xyoye.common_component.storage.file.impl.TorrentStorageFile
import com.xyoye.common_component.storage.impl.TorrentStorage
import com.xyoye.common_component.utils.thunder.ThunderManager
import com.xyoye.common_component.utils.thunder.ThunderTaskOperations
import com.xyoye.common_component.utils.thunder.model.ThunderTorrentFileInfo
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ThunderPlaybackTransportTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val operations = RecordingOperations()
    private val manager by lazy { ThunderManager(operations) { temporary.root } }
    private val torrent =
        TorrentBean(
            "test.torrent",
            2,
            "test-hash",
            true,
            "",
            listOf(ThunderTorrentFileInfo(0, "first.mkv", 5, "test.torrent"), ThunderTorrentFileInfo(1, "other.mkv", 5, "test.torrent"))
        )
    private val profile = PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.GLOBAL)
    private val storage by lazy {
        TorrentStorage(
            MediaLibraryEntity(displayName = "BT", url = "test.torrent", mediaType = MediaType.MAGNET_LINK),
            manager,
            resolveTorrent = { torrent },
            initialize = {}
        )
    }
    private val factory = PlaybackTransportFactory()
    private val leases = mutableListOf<PlaybackTransportLease>()

    @After
    fun releaseLeases() {
        leases.forEach { it.release() }
    }

    @Test
    fun sameTorrentTasksCoexistAndRetirementDeletesOnlyItsOwnCache() =
        runBlocking {
            val sentinel = File(temporary.root, "unrelated-cache").apply { writeText("keep") }
            val first = acquire(0)
            val other = acquire(1)
            val sameFile = acquire(0)
            assertEquals(emptyList<Long>(), operations.stopped)
            assertEquals(3, operations.active.size)
            assertNotEquals(first.playUrl, other.playUrl)
            assertNotEquals(first.playUrl, sameFile.playUrl)
            assertArrayEquals(intArrayOf(0), operations.selected[1L])
            assertArrayEquals(intArrayOf(1), operations.selected[2L])
            assertArrayEquals(intArrayOf(0), operations.deselected[2L])
            assertEquals(1L, manager.getTaskIdForPlayUrl(first.playUrl))
            assertEquals(2L, manager.getTaskIdForPlayUrl(other.playUrl))
            first.release()
            first.release()
            assertEquals(listOf(1L), operations.stopped)
            assertFalse(operations.directories[1L]!!.exists())
            assertTrue(operations.directories[2L]!!.exists())
            assertTrue(operations.directories[3L]!!.exists())
            assertEquals(-1L, manager.getTaskIdForPlayUrl(first.playUrl))
            assertEquals(2L, manager.getTaskIdForPlayUrl(other.playUrl))
            assertEquals("keep", sentinel.readText())
            other.release()
            assertTrue(operations.active.contains(3L))
        }

    @Test
    fun actualProviderBorrowsMetadataAndReturnsTheTaskLease() =
        runBlocking {
            val file = TorrentStorageFile(storage, torrent.subFileInfo[1])
            val result = factory.acquire(storage, file, profile) as TransportAcquisitionResult.Success
            leases.add(result.transport.lease)
            assertSame(file, result.transport.playbackFile)
            assertEquals(PlaybackTransportType.THUNDER, result.transport.lease.transportType)
            assertEquals(1L, (result.transport.lease as ThunderPlaybackTransport).taskId)
            result.transport.lease.release()
            assertEquals(listOf(1L), operations.stopped)
            assertEquals(0, operations.active.size)
        }

    @Test
    fun preparationFailureAtEachNativeStageOnlyStopsNewTask() =
        runBlocking {
            val first = acquire(0)
            for (stage in listOf("select", "start", "await", "url", "empty-url")) {
                operations.failStage = stage
                val result = factory.acquire(storage, TorrentStorageFile(storage, torrent.subFileInfo[1]), profile)
                assertTrue("Failure at $stage", result is TransportAcquisitionResult.Failure)
                assertEquals(setOf(1L), operations.active)
                assertTrue(operations.directories[1L]!!.exists())
                assertEquals(1L, manager.getTaskIdForPlayUrl(first.playUrl))
                assertFalse(operations.directories[operations.nextId]!!.exists())
                assertEquals(1, operations.stopped.count { it == operations.nextId })
            }
        }

    @Test
    fun cancellationDuringStartupReleasesOnlyItsNewTask() =
        runBlocking {
            acquire(0)
            val entered = CompletableDeferred<Unit>()
            operations.onAwait = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val pending = launch { factory.acquire(storage, TorrentStorageFile(storage, torrent.subFileInfo[1]), profile) }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(setOf(1L), operations.active)
            assertEquals(listOf(2L), operations.stopped)
            assertFalse(operations.directories[2L]!!.exists())
        }

    @Test
    fun duplicateNativeIdIsRejectedBeforeChangingOrStoppingActiveTask() =
        runBlocking {
            val first = acquire(0)
            operations.duplicateId = 1
            val result = factory.acquire(storage, TorrentStorageFile(storage, torrent.subFileInfo[1]), profile)
            assertTrue(result is TransportAcquisitionResult.Failure)
            assertEquals(1, operations.selectCalls)
            assertEquals(emptyList<Long>(), operations.stopped)
            assertTrue(operations.directories[1L]!!.exists())
            assertEquals(1L, manager.getTaskIdForPlayUrl(first.playUrl))
            assertEquals(1, temporary.root.listFiles()!!.size)
        }

    @Test
    fun staleGenerationCannotRetireAReusedNativeId() =
        runBlocking {
            val old = acquire(0)
            old.release()
            operations.duplicateId = 1
            val current = acquire(1)
            manager.releasePlaybackTask(1, old.leaseId)
            assertEquals(1L, manager.getTaskIdForPlayUrl(current.playUrl))
            assertEquals(listOf(1L), operations.stopped)
            assertTrue(operations.directories[1L]!!.exists())
        }

    @Test
    fun cleanupFailureStillDeletesOnlyOwnedDirectory() =
        runBlocking {
            val first = acquire(0)
            acquire(1)
            operations.failStop = true
            first.release()
            first.release()
            assertEquals(listOf(1L), operations.stopped)
            assertFalse(operations.directories[1L]!!.exists())
            assertTrue(operations.directories[2L]!!.exists())
            assertTrue(operations.active.contains(2L))
        }

    @Test
    fun invalidCreationRetriesWithoutStoppingInvalidOrExistingTasks() =
        runBlocking {
            acquire(0)
            operations.invalidCreation = true
            val result = factory.acquire(storage, TorrentStorageFile(storage, torrent.subFileInfo[1]), profile)
            assertEquals(TransportAcquisitionFailure.TaskCreationFailed, (result as TransportAcquisitionResult.Failure).reason)
            assertEquals(3, operations.createCalls)
            assertEquals(emptyList<Long>(), operations.stopped)
            assertEquals(1, temporary.root.listFiles()!!.size)
        }

    @Test
    fun unsupportedPlatformAndInvalidIndexAllocateNothing() =
        runBlocking {
            operations.ready = false
            val unsupported = factory.acquire(storage, TorrentStorageFile(storage, torrent.subFileInfo[0]), profile)
            assertEquals(TransportAcquisitionFailure.UnsupportedTransport, (unsupported as TransportAcquisitionResult.Failure).reason)
            operations.ready = true
            val invalid = runCatching { manager.acquirePlaybackTask(torrent, 9) }.exceptionOrNull()
            assertEquals(TransportAcquisitionFailure.UpstreamUnavailable, (invalid as TransportAcquisitionException).reason)
            assertEquals(0, operations.createCalls)
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        }

    private suspend fun acquire(index: Int): PlaybackTransportLease {
        val result = factory.acquire(storage, TorrentStorageFile(storage, torrent.subFileInfo[index]), profile)
        assertTrue("Acquisition must succeed: $result", result is TransportAcquisitionResult.Success)
        return (result as TransportAcquisitionResult.Success).transport.lease.also { leases.add(it) }
    }

    private class RecordingOperations : ThunderTaskOperations {
        var nextId = 0L
        var createCalls = 0
        var selectCalls = 0
        var duplicateId: Long? = null
        var invalidCreation = false
        var ready = true
        var failStop = false
        var failStage: String? = null
        var onAwait: suspend () -> Unit = {}
        val active = mutableSetOf<Long>()
        val stopped = mutableListOf<Long>()
        val selected = mutableMapOf<Long, IntArray>()
        val deselected = mutableMapOf<Long, IntArray>()
        val directories = mutableMapOf<Long, File>()

        override fun ensureReady(): Boolean = ready

        override fun create(parameters: BtTaskParam): Long {
            createCalls++
            if (invalidCreation) return -1
            val id = duplicateId ?: ++nextId
            if (active.add(id)) directories[id] = File(parameters.mFilePath)
            return id
        }

        override fun select(
            taskId: Long,
            selected: IntArray,
            deselected: IntArray
        ) {
            selectCalls++
            fail("select")
            this.selected[taskId] = selected.copyOf()
            this.deselected[taskId] = deselected.copyOf()
        }

        override fun start(taskId: Long) = fail("start")

        override suspend fun awaitStarted(taskId: Long): Boolean {
            onAwait()
            return failStage != "await"
        }

        override fun localUrl(path: String): String {
            fail("url")
            if (failStage == "empty-url") return ""
            val file = File(path)
            file.writeText("media")
            return "http://127.0.0.1/${checkNotNull(file.parentFile).name}/${file.name}"
        }

        override fun stop(taskId: Long) {
            stopped.add(taskId)
            active.remove(taskId)
            if (failStop) throw IOException("stop failed")
        }

        private fun fail(stage: String) {
            if (failStage == stage) throw IOException(stage)
        }
    }
}
