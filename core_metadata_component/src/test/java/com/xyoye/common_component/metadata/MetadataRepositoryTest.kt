package com.xyoye.common_component.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xyoye.common_component.database.DatabaseAccessProvider
import com.xyoye.common_component.database.DatabaseInfo
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.network.service.TmdbService
import com.xyoye.common_component.network.tmdb.TmdbFailure
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.storage.catalog.CatalogDirectorySnapshot
import com.xyoye.common_component.storage.catalog.CatalogFileSnapshot
import com.xyoye.data_component.data.tmdb.*
import com.xyoye.data_component.entity.*
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
class MetadataRepositoryTest {
    private lateinit var database: DatabaseInfo
    private lateinit var repository: MetadataRepository
    private val service = FixtureService()
    private val library = MediaLibraryEntity(10, "Fixture", "https://fixture.test", MediaType.WEBDAV_SERVER)

    @Before fun setup() =
        runBlocking {
            database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), DatabaseInfo::class.java).build()
            DatabaseProvider.overrideProviderForTest(DatabaseAccessProvider { database })
            database.getMediaLibraryDao().insert(library)
            repository = MetadataRepository(database, service)
        }

    @After fun close() {
        DatabaseProvider.overrideProviderForTest(null)
        database.close()
    }

    @Test fun sameHashDifferentCopiesAndMovieTvIdsRemainIndependent() =
        runBlocking {
            val a = asset("/one.mkv")
            val b = asset("/two.mkv")
            assertNotEquals(a.assetId, b.assetId)
            repository.bind(repository.begin(a.assetId), work("movie"), emptyList(), true)
            repository.bind(repository.begin(b.assetId), work("tv"), listOf(episode(1), episode(2)), true)
            assertNotNull(repository.dao.work("movie", 123))
            assertNotNull(repository.dao.work("tv", 123))
            assertEquals(2, repository.dao.bindingEpisodes(b.assetId).size)
            assertTrue(repository.dao.bindingEpisodes(a.assetId).isEmpty())
        }

    @Test fun manualCorrectionWinsOverLateAutomaticResult() =
        runBlocking {
            val asset = asset()
            val slow = repository.begin(asset.assetId)
            val manual = repository.begin(asset.assetId)
            repository.bind(manual, work("movie"), emptyList(), true)
            expectStale { repository.bind(slow, work("tv"), listOf(episode(1)), false) }
            assertEquals("movie", repository.dao.binding(asset.assetId)?.mediaKind)
            expectStale { repository.bind(repository.begin(asset.assetId), work("tv"), listOf(episode(1)), false) }
        }

    @Test fun ignoreSurvivesAutomaticRetry() =
        runBlocking {
            val asset = asset()
            repository.ignore(repository.begin(asset.assetId))
            expectStale { repository.bind(repository.begin(asset.assetId), work("movie"), emptyList(), false) }
            assertEquals("IGNORED", repository.dao.binding(asset.assetId)?.state)
        }

    @Test fun sourceReplacementInvalidatesOldAssetButPasswordRotationPreservesManualBinding() =
        runBlocking {
            val asset = asset()
            repository.bind(repository.begin(asset.assetId), work("movie"), emptyList(), true)
            val before = repository.begin(asset.assetId)
            MediaLibraryRepository.insert(library, invalidateRequests = true)
            assertEquals(before.source.contentGeneration, repository.source(10).contentGeneration)
            assertTrue(requireNotNull(repository.dao.binding(asset.assetId)).locked)
            expectStale { repository.ignore(before) }
            MediaLibraryRepository.insert(library.copy(url = "https://another.test"))
            assertEquals(before.source.contentGeneration + 1, repository.source(10).contentGeneration)
            expectStale { repository.begin(asset.assetId) }
        }

    @Test fun displayNameEditDoesNotInvalidateRequests() =
        runBlocking {
            val asset = asset()
            val ticket = repository.begin(asset.assetId)
            MediaLibraryRepository.insert(library.copy(displayName = "Renamed"))
            assertEquals(ticket.source, repository.source(10))
            repository.bind(ticket, work("movie"), emptyList(), true)
        }

    @Test fun sourceDeletionCleansBindingsAndRejectsLateCommit() =
        runBlocking {
            val asset = asset()
            val ticket = repository.begin(asset.assetId)
            MediaLibraryRepository.delete(library.url, library.mediaType)
            assertNull(repository.dao.asset(asset.assetId))
            expectStale { repository.bind(ticket, work("movie"), emptyList(), true) }
        }

    @Test fun observedReplacementKeepsManualEvidenceAndRequiresConfirmation() =
        runBlocking {
            val asset = asset()
            repository.bind(repository.begin(asset.assetId), work("movie"), emptyList(), true)
            repository.index(snapshot().copy(size = 200), repository.source(10))
            val binding = requireNotNull(repository.dao.binding(asset.assetId))
            assertEquals("NEEDS_CONFIRMATION", binding.state)
            assertEquals("FILE_CHANGED", binding.failure)
            assertTrue(binding.locked)
            assertEquals(123L, binding.tmdbId)
        }

    @Test fun incompleteDirectoryDoesNotMarkMissingAndCompleteDirectoryDoes() =
        runBlocking {
            val asset = asset()
            val source = repository.source(10)
            val snapshot = CatalogDirectorySnapshot("/", emptyList(), emptyList(), false)
            try {
                repository.completeDirectory(source, snapshot, "scan")
                fail()
            } catch (_: TmdbFailure) {
            }
            assertTrue(requireNotNull(repository.dao.asset(asset.assetId)).available)
            repository.completeDirectory(source, snapshot.copy(complete = true), "scan")
            assertFalse(requireNotNull(repository.dao.asset(asset.assetId)).available)
        }

    @Test fun languageFallbackAndSeasonRequestsAreShared() =
        runBlocking {
            val work = repository.details("tv", 123)
            assertEquals("English overview", work.overview)
            assertEquals("作品", work.title)
            assertTrue(work.poster?.endsWith("w500/poster.jpg") == true)
            repository.season(123, 0)
            repository.season(123, 0)
            assertEquals(1, service.seasonRequests)
        }

    @Test fun refreshFailureDoesNotEraseValidMetadata() =
        runBlocking {
            repository.details("movie", 123)
            service.fail = true
            try {
                repository.details("movie", 123, true)
                fail()
            } catch (_: TmdbFailure) {
            }
            assertEquals("作品", repository.dao.work("movie", 123)?.title)
        }

    @Test fun directoryMappingPreviewsConflictsAndCommitsVerifiedRangeOnly() =
        runBlocking {
            val first = asset("/Show - 01.mkv")
            val second = asset("/Show - 02.mkv")
            val conflict = asset("/Different - 03.mkv")
            val ticket = repository.begin(first.assetId)
            val preview = repository.previewDirectory(ticket, work("tv"), 0, 1)
            assertEquals(2, preview.count { it.conflict == null })
            assertEquals(1, preview.count { it.conflict != null })
            repository.applyDirectory(work("tv"), preview)
            assertEquals(
                2,
                repository.dao
                    .bindingEpisodes(second.assetId)
                    .single()
                    .episodeNumber
            )
            assertNull(repository.dao.binding(conflict.assetId)?.tmdbId)
        }

    @Test fun olderRefreshCannotOverwriteNewerSnapshot() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            service.movie = {
                if (++calls == 1) {
                    started.complete(Unit)
                    release.await()
                    TmdbTitle(123, title = "Old", overview = "old")
                } else {
                    TmdbTitle(123, title = "New", overview = "new")
                }
            }
            val old = async { repository.details("movie", 123, true) }
            started.await()
            repository.details("movie", 123, true)
            release.complete(Unit)
            old.await()
            assertEquals("New", repository.dao.work("movie", 123)?.title)
        }

    @Test fun confirmingAnOlderCandidateDoesNotReplaceRefreshedEpisodeText() =
        runBlocking {
            val ticket = repository.begin(asset().assetId)
            val old = episode(1).copy(title = "Old episode")
            repository.dao.putEpisodes(listOf(old.copy(title = "Refreshed episode")))
            repository.bind(ticket, work("tv"), listOf(old), true)
            assertEquals(
                "Refreshed episode",
                repository.dao
                    .episodes(123, 0)
                    .single()
                    .title
            )
            assertEquals(
                1,
                repository.dao
                    .bindingEpisodes(ticket.asset.assetId)
                    .single()
                    .episodeNumber
            )
        }

    @Test fun unknownParentPreservesObservedDirectoryAndCannotInventABatchScope() =
        runBlocking {
            val known = asset()
            val reread = repository.index(snapshot().copy(parentPath = ""), repository.source(10))
            assertEquals(known.parentPath, reread.parentPath)
            val unknown = repository.index(snapshot("/new.mkv").copy(parentPath = ""), repository.source(10))
            try {
                repository.previewDirectory(repository.begin(unknown.assetId), work("tv"), 0, 1)
                fail("An opaque document identity cannot establish a directory")
            } catch (_: IllegalArgumentException) {
            }
        }

    @Test fun refreshFailurePersistsWithoutErasingBindingOrPreventingRetry() =
        runBlocking {
            val asset = asset()
            repository.bind(repository.begin(asset.assetId), work("movie"), emptyList(), true)
            val ticket = repository.begin(asset.assetId)
            repository.recordFailure(ticket, "NOT_FOUND")
            assertEquals("MATCHED", repository.dao.binding(asset.assetId)?.state)
            assertEquals("NOT_FOUND", repository.dao.binding(asset.assetId)?.failure)
            repository.recordResult(ticket, "MATCHED")
            assertNull(repository.dao.binding(asset.assetId)?.failure)
        }

    private fun snapshot(path: String = "/file.mkv") =
        CatalogFileSnapshot(10, PlaybackFileReference("same-hash", path), "path:$path", path.substringAfterLast('/'), "/", 100, 100)

    private suspend fun asset(path: String = "/file.mkv") = repository.index(snapshot(path), repository.source(10))

    private fun work(kind: String) = MetadataWork(kind, 123, "Show", "Show", "", "2024", "", 0.0, null, null, "zh-CN", "", 1, 1)

    private fun episode(number: Int) = MetadataEpisode(123, 0, number, number.toLong(), "Episode", "", "", null)

    private suspend fun expectStale(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected stale result rejection")
        } catch (_: StaleMetadataResult) {
        }
    }

    private class FixtureService : TmdbService {
        var fail = false
        var seasonRequests = 0
        var movie: (suspend () -> TmdbTitle)? = null

        override suspend fun configuration() =
            TmdbConfiguration(TmdbImageConfiguration("https://image.tmdb.org/t/p/", listOf("w500"), listOf("w1280"), listOf("w300")))

        override suspend fun search(
            kind: String,
            query: String,
            language: String,
            page: Int,
            movieYear: Int?,
            seriesYear: Int?,
            includeAdult: Boolean
        ) = TmdbPage()

        override suspend fun details(
            kind: String,
            id: Long,
            language: String
        ): TmdbTitle {
            if (fail) throw TmdbFailure("NETWORK")
            movie?.let { return it() }
            return TmdbTitle(
                id,
                title =
                    if (language ==
                        "zh-CN"
                    ) {
                        "作品"
                    } else {
                        "Show"
                    },
                overview =
                    if (language ==
                        "en-US"
                    ) {
                        "English overview"
                    } else {
                        ""
                    },
                posterPath = "/poster.jpg",
                seasons = listOf(TmdbSeason(0))
            )
        }

        override suspend fun season(
            id: Long,
            season: Int,
            language: String
        ): TmdbSeason {
            seasonRequests++
            return TmdbSeason(season, episodes = (1..3).map { TmdbEpisode(it.toLong(), it, season, "Episode", "Overview") })
        }
    }
}
