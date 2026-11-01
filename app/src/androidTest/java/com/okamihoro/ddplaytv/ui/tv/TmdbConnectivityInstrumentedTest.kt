package com.okamihoro.ddplaytv.ui.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.metadata.AutomaticMetadataRecognizer
import com.xyoye.common_component.metadata.MetadataCoordinator
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.storage.catalog.CatalogFileSnapshot
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TmdbConnectivityInstrumentedTest {
    @Test fun actualTmdbServiceReturnsReportedTitleAndEpisode() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("tmdbLiveCheck") == "true")
            val repository = MetadataCoordinator.repository
            val results = repository.search("tv", "乱世千金倪亚·利斯顿", null, 1)
            assertEquals(1, results.totalPages)
            assertTrue(results.results.any { it.id == 312850L })
            val work = repository.details("tv", 312850L, refresh = true)
            assertTrue(work.poster?.startsWith("https://image.tmdb.org/") == true)
            assertTrue(repository.season(312850L, 1, refresh = true).any { it.episodeNumber == 1 })
            val library = MediaLibraryEntity(991076, "Live TMDB regression", "content://tmdb-live-regression", MediaType.LOCAL_STORAGE)
            DatabaseProvider.instance.getMediaLibraryDao().insert(library)
            try {
                val name = "乱世千金倪亚·利斯顿 - S01E01.mp4"
                val file =
                    CatalogFileSnapshot(
                        library.id,
                        PlaybackFileReference("live-file", "/$name", displayTitle = name),
                        "live-file",
                        name,
                        "/",
                        100L,
                        null
                    )
                val asset = repository.index(file, repository.source(library.id))
                assertEquals("MATCHED", AutomaticMetadataRecognizer(repository).recognize(asset, force = true))
                assertEquals(312850L, repository.dao.binding(asset.assetId)?.tmdbId)
                val episodes = repository.dao.bindingEpisodes(asset.assetId)
                assertEquals(listOf(1 to 1), episodes.map { it.seasonNumber to it.episodeNumber })
            } finally {
                MediaLibraryRepository.delete(library.url, library.mediaType)
            }
        }
}
