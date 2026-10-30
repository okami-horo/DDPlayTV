package com.xyoye.common_component.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xyoye.common_component.database.DatabaseInfo
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.database.repository.PlayHistoryRepository
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlayHistoryLegacyClaimTest {
    private lateinit var database: DatabaseInfo
    private lateinit var dao: PlayHistoryDao

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), DatabaseInfo::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.getPlayHistoryDao()
        DatabaseProvider.overrideProviderForTest { database }
    }

    @After
    fun tearDown() {
        DatabaseProvider.overrideProviderForTest(null)
        database.close()
    }

    @Test
    fun browsingAnotherLibraryCannotMoveAssignedHistory() =
        runBlocking {
            val original = history(storageId = 11)
            dao.insert(original)
            assertNull(claim(storageId = 22, url = "https://pc-b.test/api/v1/stream/id/1"))
            assertEquals(original.copy(id = dao.getAll().single().id), dao.getAll().single())
            assertNotNull(dao.getPlayHistory(KEY, 11))
            assertNull(dao.getPlayHistory(KEY, 22))
        }

    @Test
    fun uniqueVerifiedLegacyHistoryRetainsIdentityAndAllBindings() =
        runBlocking {
            dao.insert(history(storageId = null))
            val original = dao.getAll().single()
            val claimed = claim()!!
            assertEquals(original.copy(storageId = 11), claimed)
            assertEquals(listOf(claimed), dao.getAll())
        }

    @Test
    fun legacyHistoryWithUnknownOriginIsNotClaimed() =
        runBlocking {
            dao.insert(history(storageId = null))
            assertNull(claim(url = "https://pc-b.test/api/v1/stream/id/1"))
            assertNull(dao.getAll().single().storageId)
        }

    @Test
    fun ambiguousLegacyRowsRemainUnassigned() =
        runBlocking {
            dao.insert(history(storageId = null), history(storageId = null))
            assertNull(claim())
            assertEquals(2, dao.getAll().size)
            assertEquals(listOf(null, null), dao.getAll().map { it.storageId })
        }

    @Test
    fun exactCurrentLibraryHistoryWinsWithoutTouchingLegacyRow() =
        runBlocking {
            dao.insert(history(storageId = null), history(storageId = 11).copy(videoPosition = 8765))
            val exact = dao.getPlayHistory(KEY, 11)
            assertEquals(exact, claim())
            assertEquals(2, dao.getAll().size)
            assertEquals(1, dao.getAll().count { it.storageId == null })
        }

    @Test
    fun blankIdentityAndUrlDoNotClaimUnassignedHistory() =
        runBlocking {
            dao.insert(history(storageId = null).copy(uniqueKey = ""))
            assertNull(PlayHistoryRepository.findOrClaimLegacyHistory("", 11, MediaType.REMOTE_STORAGE, URL))
            dao.deleteAll()
            dao.insert(history(storageId = null).copy(url = ""))
            assertNull(claim(url = ""))
            assertNull(dao.getUnassignedHistory(KEY, MediaType.REMOTE_STORAGE).single().storageId)
        }

    @Test
    fun competingLibrariesCanClaimAtMostOnce() =
        runBlocking {
            dao.insert(history(storageId = null))
            val results =
                coroutineScope {
                    listOf(async { claim(storageId = 11) }, async { claim(storageId = 22) }).map { it.await() }
                }
            assertEquals(1, results.count { it != null })
            assertEquals(results.filterNotNull().single().storageId, dao.getAll().single().storageId)
        }

    private suspend fun claim(
        storageId: Int = 11,
        url: String = URL
    ) = PlayHistoryRepository.findOrClaimLegacyHistory(KEY, storageId, MediaType.REMOTE_STORAGE, url)

    private fun history(storageId: Int?) =
        PlayHistoryEntity(
            videoName = "Episode 1",
            url = URL,
            mediaType = MediaType.REMOTE_STORAGE,
            videoPosition = 91234,
            videoDuration = 100000,
            playTime = Date(1234567),
            danmuPath = "/danmu/one.xml",
            episodeId = "123",
            subtitlePath = "/subtitle/one.ass",
            uniqueKey = KEY,
            storagePath = "/one.mkv",
            storageId = storageId,
            audioPath = "/audio/one.flac"
        )

    companion object {
        private const val KEY = "same-video-hash"
        private const val URL = "https://pc-a.test/api/v1/stream/id/1"
    }
}
