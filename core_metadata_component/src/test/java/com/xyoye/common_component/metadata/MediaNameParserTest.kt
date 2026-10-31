package com.xyoye.common_component.metadata

import com.xyoye.data_component.data.tmdb.TmdbTitle
import org.junit.Assert.*
import org.junit.Test

class MediaNameParserTest {
    @Test fun preservesNumericTitlesAndSequelNumbers() {
        assertEquals("1917", MediaNameParser.parse("1917 (2019).mkv").title)
        assertEquals("Dune 2", MediaNameParser.parse("Dune.2.2024.1080p.mkv").title)
        assertEquals("君の名は", MediaNameParser.parse("君の名は (2016).mkv").title)
        assertNull(MediaNameParser.parse("Unknown.mkv").kind)
    }

    @Test fun keepsAbsoluteEpisodesUnresolvedAndSpecialsExplicit() {
        val absolute = MediaNameParser.parse("[Group] 葬送のフリーレン - 13 [1080p].mkv")
        assertEquals("葬送のフリーレン", absolute.title)
        assertEquals(13, absolute.absoluteEpisode)
        assertNull(absolute.season)
        assertEquals(0, MediaNameParser.parse("Title.S00E02.mkv").season)
        assertNull(MediaNameParser.parse("Title OVA.mkv").season)
        assertTrue(MediaNameParser.parse("Title NCOP.mkv").extra)
    }

    @Test fun mergedEpisodesHaveNoInventedPlaybackOffsets() {
        assertEquals(listOf(1, 2), MediaNameParser.parse("Title.S01E01-E02.mkv").episodes)
        assertEquals(listOf(3), MediaNameParser.parse("Title.S02E03.mkv").episodes)
    }

    @Test fun rejectsRemakesUnknownYearsAndWrongType() {
        val parsed = MediaNameParser.parse("Dune (2021).mkv").copy(kind = "movie")
        val candidate = MediaCandidate("movie", TmdbTitle(1, title = "Dune", releaseDate = "2021-01-01"))
        assertEquals(candidate, MediaMatcher.automatic(parsed, listOf(candidate)))
        assertNull(MediaMatcher.automatic(parsed.copy(year = null), listOf(candidate)))
        assertNull(MediaMatcher.automatic(parsed, listOf(candidate.copy(kind = "tv"))))
        assertNull(MediaMatcher.automatic(parsed, listOf(candidate.copy(title = candidate.title.copy(releaseDate = "1984-01-01")))))
        assertNull(MediaMatcher.automatic(parsed, listOf(candidate, candidate.copy(title = candidate.title.copy(id = 2)))))
    }

    @Test fun explicitIdsStillRequireTypeAndEpisodeValidation() {
        val parsed = MediaNameParser.parse("Title.S01E03.[tmdb-tv-123].mkv")
        assertEquals(123L, parsed.explicitId)
        assertEquals("tv", parsed.kind)
        val candidate = MediaCandidate("tv", TmdbTitle(123, name = "Title"))
        assertEquals(candidate, MediaMatcher.automatic(parsed, listOf(candidate)))
        assertNull(MediaMatcher.automatic(parsed.copy(episodes = emptyList()), listOf(candidate)))
    }
}
