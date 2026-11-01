package com.xyoye.common_component.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xyoye.common_component.database.DatabaseInfo
import com.xyoye.common_component.playback.handoff.PlayHistoryDescriptor
import com.xyoye.common_component.playback.handoff.SeriesPlaybackDescriptor
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.data_component.entity.*
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MetadataPlaybackRequestsTest {
    private lateinit var database: DatabaseInfo
    private lateinit var requests: MetadataPlaybackRequests
    private val dao get() = database.getMetadataDao()

    @Before fun setup() =
        runBlocking {
            database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), DatabaseInfo::class.java).build()
            for (id in 1..2) {
                database.getMediaLibraryDao().insert(MediaLibraryEntity(id, "Source $id", "fixture:$id", MediaType.SMB_SERVER))
                dao.putSource(CatalogSourceState(id))
            }
            requests = MetadataPlaybackRequests(MetadataRepository(database))
        }

    @After fun close() = database.close()

    @Test fun wholeSeriesIsSortedAcrossDirectoriesSeasonsAndSourcesWithoutFetchingMetadata() =
        runBlocking {
            val secondSeason = asset(2, 1, 1)
            val first = asset(1, 1, 1)
            val variant = asset(1, 1, 2)
            asset(0, 1, 1)
            val unknown = asset(1, 99, 1)
            dao.clearEpisodes(unknown.assetId)
            val queue = requests.asset(secondSeason.assetId) as SeriesPlaybackDescriptor
            assertEquals(listOf(1 to 1, 2 to 1), queue.entries.map { it.seasonNumber to it.episodeNumber })
            assertEquals(1, queue.selectedEpisode)
            assertEquals(secondSeason.assetId, queue.selectedFile!!.assetId)
            assertEquals(
                listOf(first.assetId, variant.assetId),
                queue.entries
                    .first()
                    .files
                    .map { it.assetId }
            )
            assertEquals("第 2 季 · 第 1 集", queue.displayTitle())
            assertEquals(first.parentPath != secondSeason.parentPath, true)
            assertTrue(requests.asset(unknown.assetId) is StoredLibraryFileDescriptor)
        }

    @Test fun historyUsesSourceAndKeyAndKeepsTheActualFileVersion() =
        runBlocking {
            val one = asset(1, 1, 1)
            val two = asset(1, 1, 2).copy(uniqueKey = one.uniqueKey)
            dao.putAsset(two)
            asset(1, 2, 2)
            val history =
                PlayHistoryEntity(
                    id = 8,
                    videoName = "episode",
                    url = "private",
                    mediaType = MediaType.SMB_SERVER,
                    uniqueKey = one.uniqueKey,
                    storageId = 2,
                    videoPosition = 777
                )
            val queue = requests.history(history) as SeriesPlaybackDescriptor
            assertEquals(two.assetId, queue.selectedFile!!.assetId)
            assertEquals(2, queue.preferredLibraryId)
            assertEquals(2, queue.queue().size)
            assertEquals(777L, history.videoPosition)
            assertTrue(requests.history(history.copy(storageId = null)) is PlayHistoryDescriptor)
            assertTrue(requests.history(history.copy(mediaType = MediaType.FTP_SERVER)) is PlayHistoryDescriptor)
            assertTrue(requests.history(history.copy(uniqueKey = "absent")) is PlayHistoryDescriptor)
        }

    @Test fun specialsStaySeparateAndMergedFileHintsRetainTheChosenEpisode() =
        runBlocking {
            asset(1, 1, 1)
            val merged = asset(0, 1, 1)
            dao.putBindingEpisodes(listOf(MediaBindingEpisode(merged.assetId, 0, 1, 0), MediaBindingEpisode(merged.assetId, 0, 2, 1)))
            asset(0, 3, 2)
            val queue = requests.asset(merged.assetId, 0, 2) as SeriesPlaybackDescriptor
            assertEquals(listOf(0, 0, 0), queue.entries.map { it.seasonNumber })
            assertEquals(1, queue.selectedEpisode)
            assertEquals(listOf(1, 2), queue.queue().map { it.selectedEpisode })
            assertTrue(queue.displayTitle().contains("合并集"))
        }

    @Test fun unavailableReplacedFilesAndOldSourceGenerationsAreExcluded() =
        runBlocking {
            val selected = asset(1, 1, 1)
            val missing = asset(1, 2, 1)
            dao.putAsset(missing.copy(available = false))
            val changed = asset(1, 3, 1)
            dao.putBinding(dao.binding(changed.assetId)!!.copy(failure = "FILE_CHANGED"))
            asset(1, 4, 2)
            dao.putSource(CatalogSourceState(2, 2))
            database.getMediaLibraryDao().insert(MediaLibraryEntity(3, "Legacy", "legacy", MediaType.REMOTE_STORAGE))
            dao.putSource(CatalogSourceState(3))
            asset(1, 5, 3)
            val queue = requests.asset(selected.assetId) as SeriesPlaybackDescriptor
            assertEquals(listOf(1), queue.entries.map { it.episodeNumber })
            try {
                requests.asset(changed.assetId)
                fail("Changed anchor must not silently pick a different file")
            } catch (_: IllegalArgumentException) {
            }
        }

    @Test fun movieAndUnmatchedFilesRemainSingletonsAndLongSeriesAreNotTruncated() =
        runBlocking {
            val movie = asset(1, 1, 1)
            dao.putBinding(dao.binding(movie.assetId)!!.copy(mediaKind = "movie"))
            assertTrue(requests.asset(movie.assetId) is StoredLibraryFileDescriptor)
            dao.putBinding(MediaBinding(movie.assetId))
            assertTrue(requests.asset(movie.assetId) is StoredLibraryFileDescriptor)
            val files = (2..211).map { asset(1, it, 1) }
            val queue = requests.asset(files.last().assetId) as SeriesPlaybackDescriptor
            assertEquals(210, queue.queue().size)
            assertEquals(209, queue.selectedEpisode)
        }

    private suspend fun asset(
        season: Int,
        number: Int,
        library: Int
    ): MediaAsset {
        val path = "/season$season/$library-$number.mkv"
        val raw =
            MediaAsset(
                libraryId = library,
                contentGeneration = 1,
                locatorKey = path,
                uniqueKey = "$library:$path",
                storagePath = path,
                fileName = path.substringAfterLast('/'),
                parentPath = "/season$season",
                discoveredAt = 1
            )
        val asset = raw.copy(assetId = dao.putAsset(raw))
        dao.putBinding(MediaBinding(asset.assetId, "MATCHED", "tv", 77))
        dao.putBindingEpisodes(listOf(MediaBindingEpisode(asset.assetId, season, number, 0)))
        return asset
    }
}
