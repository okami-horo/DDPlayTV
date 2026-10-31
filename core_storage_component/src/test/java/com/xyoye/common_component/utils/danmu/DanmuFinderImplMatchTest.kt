package com.xyoye.common_component.utils.danmu

import com.xyoye.common_component.utils.danmu.query.DanmuQuery
import com.xyoye.common_component.utils.danmu.source.DanmuSource
import com.xyoye.data_component.data.DanmuAnimeData
import com.xyoye.data_component.data.DanmuContentData
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.data.MatchMethod
import com.xyoye.data_component.data.MatchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DanmuFinderImplMatchTest {
    @Test
    fun getMatchedPassesSourceMetadataAndKeepsFuzzyCandidates() = runBlocking {
        val episode = DanmuEpisodeData(episodeId = "100", animeTitle = "Anime")
        val query = FakeDanmuQuery(MatchResult(false, listOf(episode), MatchMethod.FILE_NAME))
        val finder = DanmuFinderImpl(query)

        val result = finder.getMatched(FakeDanmuSource())

        assertEquals(query.result, result)
        assertEquals("hash", query.hash)
        assertEquals("Episode 01", query.fileName)
        assertEquals(1234L, query.fileSize)
        assertEquals(90, query.videoDuration)
    }

    @Test
    fun downloadMatchedIgnoresFuzzyCandidates() = runBlocking {
        val episode = DanmuEpisodeData(episodeId = "100", animeTitle = "Anime")
        val query = FakeDanmuQuery(MatchResult(false, listOf(episode), MatchMethod.FILE_NAME))
        val finder = DanmuFinderImpl(query)

        assertNull(finder.downloadMatched(FakeDanmuSource()))
    }

    private class FakeDanmuSource : DanmuSource {
        override suspend fun hash(): String = "hash"
        override suspend fun fileName(): String = "Episode 01"
        override suspend fun fileSize(): Long = 1234L
        override suspend fun durationMs(): Long = 90_000L
    }

    private class FakeDanmuQuery(
        val result: MatchResult
    ) : DanmuQuery {
        var hash: String? = null
        var fileName: String? = null
        var fileSize: Long? = null
        var videoDuration: Int? = null

        override suspend fun match(
            hash: String,
            fileName: String?,
            fileSize: Long,
            videoDuration: Int
        ): MatchResult {
            this.hash = hash
            this.fileName = fileName
            this.fileSize = fileSize
            this.videoDuration = videoDuration
            return result
        }

        override suspend fun search(text: String): List<DanmuAnimeData> = emptyList()

        override suspend fun getContentByEpisodeId(
            episodeId: String,
            withRelated: Boolean
        ): List<DanmuContentData> = emptyList()
    }
}
