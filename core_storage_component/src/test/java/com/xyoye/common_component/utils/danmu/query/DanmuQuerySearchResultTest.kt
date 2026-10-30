package com.xyoye.common_component.utils.danmu.query

import com.xyoye.data_component.data.DanmuAnimeData
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.data.DanmuSearchData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DanmuQuerySearchResultTest {
    @Test fun failureIsPreservedWhileLegacySearchKeepsItsEmptyFallback() =
        runBlocking {
            val error = IllegalStateException("offline")
            val query = DanmuQueryImpl { Result.failure(error) }
            assertSame(error, query.searchResult("query").exceptionOrNull())
            assertTrue(query.search("query").isEmpty())
        }

    @Test fun trueEmptyAndMappedResultsRemainSuccessful() =
        runBlocking {
            val query =
                DanmuQueryImpl {
                    Result.success(
                        DanmuSearchData(
                            false,
                            listOf(DanmuAnimeData(animeId = 7, animeTitle = "title", episodes = listOf(DanmuEpisodeData(episodeId = "8"))))
                        )
                    )
                }
            val episode =
                query
                    .searchResult("query")
                    .getOrThrow()
                    .single()
                    .episodes
                    .single()
            assertEquals(7, episode.animeId)
            assertEquals("title", episode.animeTitle)
            assertTrue(DanmuQueryImpl { Result.success(DanmuSearchData(false)) }.searchResult("query").getOrThrow().isEmpty())
        }

    @Test fun cancellationInResultPropagatesInsteadOfBecomingEmpty() =
        runBlocking {
            val query = DanmuQueryImpl { Result.failure(CancellationException("cancelled")) }
            try {
                query.search("query")
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                // Expected.
            }
        }
}
