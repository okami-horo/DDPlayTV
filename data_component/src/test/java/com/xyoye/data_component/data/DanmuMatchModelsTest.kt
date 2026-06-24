package com.xyoye.data_component.data

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DanmuMatchModelsTest {
    @Test
    fun episodeParsesShiftFromMatchResponse() {
        val json =
            """
            {
              "animeId": 10,
              "animeTitle": "Anime",
              "episodeId": "20",
              "episodeTitle": "Episode",
              "shift": 1.5
            }
            """.trimIndent()

        val episode = Moshi.Builder().build().adapter(DanmuEpisodeData::class.java).fromJson(json)

        assertEquals(1.5, episode?.shift ?: 0.0, 0.0)
    }

    @Test
    fun matchResultCanRepresentFuzzyCandidates() {
        val episode = DanmuEpisodeData(episodeId = "20")

        val result = MatchResult(
            isMatched = false,
            episodes = listOf(episode),
            matchMethod = MatchMethod.FILE_NAME,
        )

        assertFalse(result.isMatched)
        assertEquals(listOf(episode), result.episodes)
        assertEquals(MatchMethod.FILE_NAME, result.matchMethod)
    }
}
