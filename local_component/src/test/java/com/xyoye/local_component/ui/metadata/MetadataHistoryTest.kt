package com.xyoye.local_component.ui.metadata

import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.MediaBinding
import com.xyoye.data_component.entity.MediaBindingEpisode
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class MetadataHistoryTest {
    @Test fun seriesCardsKeepAStableWorkKeyButResumeTheNewestPhysicalFile() {
        val rows = projectMetadataHistory(state())
        assertEquals(listOf(2, 1), rows.map { it.history.id })
        assertEquals("tv:42", rows.distinctBy { it.key }.single().key)
        assertEquals("第 2 季 · 第 3 集 · 本集 42%", rows.first().subtitle)
        assertEquals(2L, rows.first().asset?.assetId)
        assertEquals(1, initialDetailSeason(listOf(0, 1, 2), 1, rows.first()))
        assertEquals(2, initialDetailSeason(listOf(0, 1, 2), null, rows.first()))
    }

    @Test fun identicalFileKeysInDifferentLibrariesNeverShareHistory() {
        val state = state().copy(assets = listOf(asset(1), asset(2).copy(libraryId = 8)))
        val rows = projectMetadataHistory(state)
        assertEquals("history:2", rows.first().key)
        assertNull(rows.first().asset)
        assertEquals("tv:42", rows.last().key)
    }

    @Test fun replacedAmbiguousAndUnidentifiedFilesStayFileRecords() {
        val original = state()
        val replaced = projectMetadataHistory(original.copy(assets = original.assets.map { it.copy(contentGeneration = 2) }))
        assertTrue(replaced.all { it.key.startsWith("history:") })
        val changed = projectMetadataHistory(original.copy(bindings = original.bindings.map { it.copy(failure = "FILE_CHANGED") }))
        assertTrue(changed.all { it.tmdbId == null && it.episodes.isEmpty() && it.asset == null })
        val ambiguous = projectMetadataHistory(original.copy(assets = original.assets + asset(2).copy(assetId = 3)))
        assertNull(ambiguous.first().asset)
        assertTrue(projectMetadataHistory(original.copy(bindings = emptyList())).all { it.key.startsWith("history:") })
    }

    @Test fun missingLatestFileRemainsTheResumeTargetAndNeverFallsBackToOlderCopy() {
        val rows = projectMetadataHistory(state().let { it.copy(assets = it.assets.map { a -> a.copy(available = a.assetId != 2L) }) })
        assertEquals(
            2,
            rows
                .distinctBy { it.key }
                .single()
                .history.id
        )
        assertFalse(rows.first().asset!!.available)
    }

    @Test fun mergedEpisodesUseFileProgressAndSpecialsHaveASeparateLabel() {
        val s = state().copy(episodes = listOf(MediaBindingEpisode(2, 0, 3, 0), MediaBindingEpisode(2, 0, 4, 1)))
        assertEquals("特别篇 · 第 3–4 集 · 文件 42%", projectMetadataHistory(s).first().subtitle)
        assertEquals("合并集", projectMetadataHistory(s.copy(episodes = s.episodes + MediaBindingEpisode(2, 0, 9, 2))).first().episodeLabel)
        assertEquals(1, initialDetailSeason(listOf(0, 1, 2), null, null))
        assertEquals(0, initialDetailSeason(listOf(0), null, null))
    }

    @Test fun unknownDurationZeroAndNearEndDoNotInventCompletionOrProgress() {
        val h = history(1)
        assertEquals("上次观看至 12:34", historyProgress(h.copy(videoPosition = 754000, videoDuration = 0)))
        assertNull(historyPercent(h.copy(videoDuration = 0)))
        assertEquals("最近播放", historyProgress(h.copy(videoPosition = 0, videoDuration = 0)))
        assertEquals("上次观看至 99%", historyProgress(h.copy(videoPosition = 99000)))
        assertEquals("上次观看至 0%", historyProgress(h.copy(videoPosition = 0)))
        assertEquals(100, historyPercent(h.copy(videoPosition = Long.MAX_VALUE)))
    }

    @Test fun movieAndUnknownEpisodeHaveHonestLabelsAndRecognitionKeepsHistoryIdentity() {
        val original = state()
        val files = projectMetadataHistory(original.copy(bindings = emptyList()))
        val known = projectMetadataHistory(original.copy(episodes = emptyList()))
        assertEquals(files.first().history.id, known.first().history.id)
        assertEquals("季集待确认 · 本集 42%", known.first().subtitle)
        val movie = projectMetadataHistory(original.copy(bindings = original.bindings.map { it.copy(mediaKind = "movie") })).first()
        assertEquals("movie:42", movie.key)
        assertEquals("上次观看至 42%", movie.subtitle)
        assertTrue(movie.episodes.isEmpty())
    }

    private fun state() =
        MetadataLibraryState(
            assets = listOf(asset(1), asset(2)),
            bindings = listOf(MediaBinding(1, "MATCHED", "tv", 42), MediaBinding(2, "MATCHED", "tv", 42)),
            episodes = listOf(MediaBindingEpisode(1, 1, 1, 0), MediaBindingEpisode(2, 2, 3, 0)),
            history = listOf(history(1), history(2)),
            loaded = true
        )

    private fun history(id: Int) =
        PlayHistoryEntity(
            id = id,
            videoName = "raw-$id.mkv",
            url = "/$id",
            mediaType = MediaType.LOCAL_STORAGE,
            uniqueKey = "key$id",
            storageId = 7,
            videoDuration = 100000,
            videoPosition = 42000,
            playTime = Date(id.toLong())
        )

    private fun asset(id: Long) =
        MediaAsset(
            assetId = id,
            libraryId = 7,
            contentGeneration = 1,
            locatorKey = "/$id",
            uniqueKey = "key$id",
            storagePath = "/$id",
            fileName = "$id.mkv",
            parentPath = "/",
            discoveredAt = 0
        )
}
