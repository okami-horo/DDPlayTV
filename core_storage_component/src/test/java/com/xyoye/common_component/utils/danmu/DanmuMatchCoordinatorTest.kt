package com.xyoye.common_component.utils.danmu

import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.utils.danmu.cache.CachedMatch
import com.xyoye.common_component.utils.danmu.cache.DanmuMatchCacheStore
import com.xyoye.common_component.utils.danmu.helper.DanmuHashCalculator
import com.xyoye.common_component.utils.danmu.query.DanmuQuery
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.data.DanmuAnimeData
import com.xyoye.data_component.data.DanmuContentData
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.data.DanmuRelatedUrlData
import com.xyoye.data_component.data.MatchMethod
import com.xyoye.data_component.data.MatchResult
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class DanmuMatchCoordinatorTest {
    @Test
    fun storageMatcherSupportsOnlyBilibiliStorage() {
        assertTrue(StorageDanmuMatcher.isSupported(FakeVideoSource(mediaType = MediaType.BILIBILI_STORAGE)))
        assertFalse(StorageDanmuMatcher.isSupported(FakeVideoSource(mediaType = MediaType.LOCAL_STORAGE)))
    }

    @Test
    fun coordinatorReturnsFuzzyCandidatesWithoutDownloading() = runBlocking {
        val dir = File("/tmp/danmu-match-${System.nanoTime()}")
        val file = File(dir, "Episode 01.mkv")
        dir.mkdirs()
        file.writeText("video")
        val hash = DanmuHashCalculator.calculate(ByteArrayInputStream(file.readBytes()))
        val episode = DanmuEpisodeData(episodeId = "100", animeTitle = "Anime")
        val finder = FakeDanmuFinder()
        val query = FakeDanmuQuery(MatchResult(false, listOf(episode), MatchMethod.FILE_NAME))
        val coordinator = DanmuMatchCoordinator(finder, query, FakeDanmuMatchCache())

        val outcome = coordinator.match(FakeVideoSource(url = file.path.replace('\\', '/')), DanmuMatchCoordinator.MatchMode.AUTO)

        assertEquals(MatchOutcome.Candidates(listOf(episode)), outcome)
        assertEquals(hash, query.hash)
        assertEquals("Episode 01", query.fileName)
        assertEquals(file.length(), query.fileSize)
        assertEquals(0, finder.downloadCount)
    }

    private class FakeVideoSource(
        private val url: String = "file:///tmp/video.mkv",
        private val mediaType: MediaType = MediaType.LOCAL_STORAGE,
    ) : BaseVideoSource(0, listOf(url)) {
        override fun getVideoUrl(): String = url
        override fun getVideoTitle(): String = "Video"
        override fun getCurrentPosition(): Long = 0L
        override fun getMediaType(): MediaType = mediaType
        override fun getHttpHeader(): Map<String, String>? = null
        override fun getUniqueKey(): String = "key"
        override fun getStorageId(): Int = 0
        override fun getStoragePath(): String? = null
        override fun indexTitle(index: Int): String = url
    }

    private class FakeDanmuFinder : DanmuFinder {
        var downloadCount = 0

        override suspend fun getMatched(source: com.xyoye.common_component.utils.danmu.source.DanmuSource): MatchResult? = null

        override suspend fun downloadMatched(source: com.xyoye.common_component.utils.danmu.source.DanmuSource): LocalDanmuBean? = null

        override suspend fun downloadEpisode(
            episode: DanmuEpisodeData,
            withRelated: Boolean
        ): LocalDanmuBean? {
            downloadCount++
            return LocalDanmuBean("/tmp/danmu.xml", episode.episodeId)
        }

        override suspend fun downloadRelated(
            episode: DanmuEpisodeData,
            related: List<DanmuRelatedUrlData>
        ): LocalDanmuBean? = null

        override suspend fun search(text: String): List<DanmuAnimeData> = emptyList()

        override suspend fun getRelated(episodeId: String): List<DanmuRelatedUrlData> = emptyList()

        override suspend fun saveStream(
            episode: DanmuEpisodeData,
            inputStream: java.io.InputStream
        ): LocalDanmuBean? = null
    }

    private class FakeDanmuQuery(
        private val result: MatchResult
    ) : DanmuQuery {
        var hash: String? = null
        var fileName: String? = null
        var fileSize: Long? = null

        override suspend fun match(
            hash: String,
            fileName: String?,
            fileSize: Long,
            videoDuration: Int
        ): MatchResult {
            this.hash = hash
            this.fileName = fileName
            this.fileSize = fileSize
            return result
        }

        override suspend fun search(text: String): List<DanmuAnimeData> = emptyList()

        override suspend fun source(episodeId: String): List<DanmuRelatedUrlData> = emptyList()

        override suspend fun getContentByEpisodeId(
            episodeId: String,
            withRelated: Boolean
        ): List<DanmuContentData> = emptyList()

        override suspend fun getContentByUrl(url: String): List<DanmuContentData> = emptyList()
    }

    private class FakeDanmuMatchCache : DanmuMatchCacheStore {
        override fun getSuccess(hash: String, fileSize: Long): CachedMatch? = null
        override fun putSuccess(hash: String, fileSize: Long, match: CachedMatch) = Unit
        override fun hasRecentFail(hash: String, fileSize: Long): Boolean = false
        override fun putFail(hash: String, fileSize: Long) = Unit
    }
}
