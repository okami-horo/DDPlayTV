package com.xyoye.common_component.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xyoye.common_component.database.DatabaseInfo
import com.xyoye.common_component.network.service.TmdbService
import com.xyoye.common_component.network.tmdb.TmdbFailure
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.services.PlaybackMetadataFile
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
class PlaybackMetadataIndexerTest {
    private lateinit var db: DatabaseInfo
    private lateinit var repository: MetadataRepository
    private lateinit var indexer: PlaybackMetadataIndexer
    private val service = Service()
    private val library = MediaLibraryEntity(10, "Source", "https://fixture.test", MediaType.WEBDAV_SERVER)
    private var files = emptyList<CatalogFileSnapshot>()
    private var clock = 1000L
    private var directoryReads = 0
    private var complete = true

    @Before fun setup() =
        runBlocking {
            db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), DatabaseInfo::class.java).build()
            db.getMediaLibraryDao().insert(library)
            repository = MetadataRepository(db, service)
            indexer =
                PlaybackMetadataIndexer(repository, { _, ref -> files.single { it.reference.uniqueKey == ref.uniqueKey } }, { _, path ->
                    directoryReads++
                    CatalogDirectorySnapshot(path, files, listOf("/unrelated-subfolder"), complete)
                }, { clock })
        }

    @After fun close() {
        db.close()
    }

    @Test fun ordinaryMovieNamesReachPersistentBindingsWithoutTypeHints() =
        runBlocking {
            for ((name, title, year) in listOf(
                Triple("1917 (2019).mkv", "1917", "2019"),
                Triple("君の名は.mkv", "君の名は", "2016"),
                Triple("Dune.2.2024.1080p.mkv", "Dune 2", "2024")
            )) {
                val candidate = TmdbTitle(service.titles.size + 1L, title = title, releaseDate = year, overview = "Synopsis")
                service.titles += "movie" to candidate
                files = listOf(file(name))
                indexer.process(input(files.single()), name)
                val binding = repository.dao.binding(asset(files.single()).assetId)!!
                assertEquals(name, "MATCHED", binding.state)
                assertEquals(candidate.id, binding.tmdbId)
            }
            assertEquals(0, directoryReads)
            assertTrue(db.getPlayHistoryDao().getPlayHistory("unused", 10) == null)
        }

    @Test fun thirdEpisodeDiscoversTwelveOnlyInTheSameSeriesAndPreservesPlaybackHistory() =
        runBlocking {
            service.titles += "tv" to TmdbTitle(42, name = "Show", firstAirDate = "2023", overview = "Synopsis")
            files = (1..12).map { file("Show (2023) S01E${it.toString().padStart(2, '0')}.mkv") } +
                listOf(
                    file("Other S01E01.mkv"),
                    file("Movie (2024).mkv"),
                    file("Show (2023) S01E01 trailer.mkv"),
                    file("Show (2023) S02E01.mkv")
                )
            val selected = files[2]
            val history =
                PlayHistoryEntity(
                    videoName = selected.fileName,
                    url = selected.reference.storagePath,
                    mediaType = library.mediaType,
                    uniqueKey = selected.reference.uniqueKey,
                    storageId = 10,
                    videoPosition = 8000,
                    videoDuration = 90000
                )
            db.getPlayHistoryDao().insert(history)
            indexer.process(input(selected), "first")
            val assets = repository.dao.directoryAssets(10, 1, "/shows")
            assertEquals(13, assets.size)
            assertEquals(13, assets.count { repository.dao.binding(it.assetId)?.state == "MATCHED" })
            assertEquals(1, service.searches)
            assertEquals(2, service.seasons)
            assertEquals(8000L, db.getPlayHistoryDao().getPlayHistory(selected.reference.uniqueKey, 10)?.videoPosition)
            assertNull(db.getPlayHistoryDao().getPlayHistory(files[0].reference.uniqueKey, 10))
            indexer.process(input(files[3]), "next")
            assertEquals(1, directoryReads)
            files = files + file("Show (2023) S01E13.mkv")
            clock += 60_001
            indexer.process(input(selected), "later")
            assertEquals(2, directoryReads)
            assertEquals(14, repository.dao.directoryAssets(10, 1, "/shows").size)
        }

    @Test fun ambiguousAndTruncatedSearchesRemainUnboundAndAreSuppressedUntilRetry() =
        runBlocking {
            service.titles +=
                listOf(
                    "movie" to TmdbTitle(1, title = "Dune", releaseDate = "1984"),
                    "movie" to TmdbTitle(2, title = "Dune", releaseDate = "2021")
                )
            files = listOf(file("Dune.mkv"))
            val input = input(files.single())
            indexer.process(input, "ambiguous")
            val asset = asset(files.single())
            assertEquals("AMBIGUOUS", repository.dao.binding(asset.assetId)?.failure)
            val searches = service.searches
            indexer.process(input, "suppressed")
            assertEquals(searches, service.searches)
            service.titles.removeAt(0)
            service.pages = 10
            indexer.process(input, "truncated", force = true)
            assertEquals("SEARCH_LIMIT", repository.dao.binding(asset.assetId)?.failure)
            assertNull(repository.dao.binding(asset.assetId)?.tmdbId)
            service.pages = 1
            indexer.process(input, "retry", force = true)
            assertEquals("MATCHED", repository.dao.binding(asset.assetId)?.state)
        }

    @Test fun transientFailureHasPersistentCooldownAndThenRetriesWithoutManualIntervention() =
        runBlocking {
            service.titles += "movie" to TmdbTitle(1, title = "Film", overview = "Synopsis")
            files = listOf(file("Film.mkv"))
            val asset = asset(files.single())
            service.failure = "NETWORK"
            AutomaticMetadataRecognizer(repository).recognize(asset)
            val attempt = repository.dao.binding(asset.assetId)!!.attemptedAt
            assertTrue(attempt > 0)
            val calls = service.searches
            service.failure = null
            assertEquals("FAILED", AutomaticMetadataRecognizer(repository) { attempt + 1000 }.recognize(asset))
            assertEquals(calls, service.searches)
            assertEquals("MATCHED", AutomaticMetadataRecognizer(repository) { attempt + 300001 }.recognize(asset))
        }

    @Test fun renamingAnIgnoredFileDoesNotSilentlyOptItBackIn() =
        runBlocking {
            files = listOf(file("Old.mkv"))
            val asset = asset(files.single())
            repository.ignore(repository.begin(asset.assetId))
            val renamed = repository.index(files.single().copy(fileName = "New.mkv"), repository.source(10))
            assertEquals("IGNORED", AutomaticMetadataRecognizer(repository).recognize(renamed))
            assertEquals(0, service.searches)
        }

    @Test fun mergedSpecialMissingAndAbsoluteEpisodesAreDiagnosedWithoutInventedOffsets() =
        runBlocking {
            service.titles += "tv" to TmdbTitle(42, name = "Show", overview = "Synopsis")
            files = listOf(file("Show S00E01-E02.mkv"), file("Show S01E99.mkv"), file("[Group] Show - 13 [1080p].mkv"))
            indexer.process(input(files.first()), "special")
            assertEquals(listOf(1, 2), repository.dao.bindingEpisodes(asset(files.first()).assetId).map { it.episodeNumber })
            assertEquals("EPISODE_MISSING", repository.dao.binding(asset(files[1]).assetId)?.failure)
            assertEquals("ABSOLUTE_EPISODE", repository.dao.binding(asset(files[2]).assetId)?.failure)
        }

    @Test fun partialDirectoryNeverMarksOldFilesMissingAndUnknownParentDoesNotGuess() =
        runBlocking {
            service.titles += "tv" to TmdbTitle(42, name = "Show", overview = "Synopsis")
            val old = repository.index(file("Old S01E01.mkv"), repository.source(10))
            files = listOf(file("Show S01E01.mkv"), file("Show S01E02.mkv"))
            complete = false
            indexer.process(input(files[0]), "partial")
            assertEquals("PARTIAL", repository.dao.scan("partial")?.state)
            assertTrue(repository.dao.asset(old.assetId)!!.available)
            val opaque = file("Show S01E03.mkv").copy(parentPath = "")
            files = listOf(opaque)
            indexer.process(input(opaque).copy(parentPath = null, mediaType = MediaType.EXTERNAL_STORAGE), "opaque")
            assertEquals("PARENT_UNAVAILABLE", repository.dao.scan("opaque")?.failure)
            assertEquals(1, directoryReads)
        }

    @Test fun renameWithStableIdentityInvalidatesLateResultAndKeepsOldBindingUntilSuccess() =
        runBlocking {
            service.titles +=
                listOf(
                    "movie" to TmdbTitle(1, title = "Old", overview = "Synopsis"),
                    "movie" to TmdbTitle(2, title = "New", overview = "Synopsis")
                )
            files = listOf(file("Old.mkv"))
            indexer.process(input(files.single()), "old")
            val saved = asset(files.single())
            val stale = repository.begin(saved.assetId)
            files = listOf(files.single().copy(fileName = "New.mkv"))
            service.failure = "NETWORK"
            indexer.process(input(files.single()), "offline")
            assertEquals(1L, repository.dao.binding(saved.assetId)?.tmdbId)
            try {
                repository.bind(stale, repository.dao.work("movie", 1)!!, emptyList(), false)
                fail("Stale rename result")
            } catch (
                _: StaleMetadataResult
            ) {
            }
            service.failure = null
            indexer.process(input(files.single()), "retry", force = true)
            assertEquals(2L, repository.dao.binding(saved.assetId)?.tmdbId)
        }

    @Test fun legacyManualAndIgnoredBindingsSurviveUntilExplicitSuccessfulReidentification() =
        runBlocking {
            service.titles += "movie" to TmdbTitle(1, title = "Film", overview = "Synopsis")
            files = listOf(file("Film.mkv"))
            val asset = asset(files.single())
            repository.ignore(repository.begin(asset.assetId))
            indexer.process(input(files.single()), "ignored")
            assertEquals("IGNORED", repository.dao.binding(asset.assetId)?.state)
            assertEquals(0, service.searches)
            indexer.process(input(files.single()), "retry", force = true)
            assertEquals("MATCHED", repository.dao.binding(asset.assetId)?.state)
            repository.bind(repository.begin(asset.assetId), repository.dao.work("movie", 1)!!, emptyList(), true)
            indexer.process(input(files.single()), "manual")
            assertTrue(repository.dao.binding(asset.assetId)!!.locked)
        }

    @Test fun changedSourceRejectsLateNetworkResult() =
        runBlocking {
            service.titles += "movie" to TmdbTitle(1, title = "Film", overview = "Synopsis")
            files = listOf(file("Film.mkv"))
            val input = input(files.single())
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            service.gate = {
                started.complete(Unit)
                release.await()
            }
            val work = async { runCatching { indexer.process(input, "stale") } }
            started.await()
            repository.dao.putSource(input.source.copy(revision = 2))
            release.complete(Unit)
            assertTrue(work.await().exceptionOrNull() is StaleMetadataResult)
            assertNull(repository.dao.binding(asset(files.single()).assetId)?.tmdbId)
        }

    private fun file(name: String) =
        CatalogFileSnapshot(
            10,
            PlaybackFileReference(name, "/shows/$name", displayTitle = name),
            "path:/shows/$name",
            name,
            "/shows",
            100,
            100
        )

    private suspend fun input(file: CatalogFileSnapshot) =
        PlaybackMetadataFile(repository.source(10), library.mediaType, file.reference, file.parentPath)

    private suspend fun asset(file: CatalogFileSnapshot) =
        repository.dao.asset(10, 1, file.locatorKey) ?: repository.index(file, repository.source(10))

    private class Service : TmdbService {
        val titles = mutableListOf<Pair<String, TmdbTitle>>()
        var searches = 0
        var seasons = 0
        var pages = 1
        var failure: String? = null
        var gate: (suspend () -> Unit)? = null

        override suspend fun search(
            kind: String,
            query: String,
            language: String,
            page: Int,
            movieYear: Int?,
            seriesYear: Int?,
            includeAdult: Boolean
        ): TmdbPage {
            searches++
            failure?.let { throw TmdbFailure(it) }
            gate?.also { gate = null }?.invoke()
            return TmdbPage(page, titles.filter { it.first == kind && it.second.displayTitle() == query }.map { it.second }, pages)
        }

        override suspend fun details(
            kind: String,
            id: Long,
            language: String
        ) = titles.single { it.first == kind && it.second.id == id }.second

        override suspend fun season(
            id: Long,
            season: Int,
            language: String
        ): TmdbSeason {
            seasons++
            return TmdbSeason(season, episodes = (1..13).map { TmdbEpisode(it.toLong(), it, season, "Episode", "Synopsis") })
        }

        override suspend fun configuration() = TmdbConfiguration(TmdbImageConfiguration("https://image.tmdb.org/t/p/"))
    }
}
