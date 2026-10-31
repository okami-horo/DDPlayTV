package com.xyoye.common_component.metadata

import com.xyoye.data_component.data.tmdb.TmdbTitle
import java.text.Normalizer
import java.util.Locale

data class MediaCandidate(
    val kind: String,
    val title: TmdbTitle
)

object MediaMatcher {
    fun normalize(value: String): String =
        Normalizer
            .normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    fun automatic(
        parsed: ParsedMediaName,
        candidates: List<MediaCandidate>
    ): MediaCandidate? {
        if (parsed.extra || parsed.absoluteEpisode != null || parsed.kind == null) return null
        val matching =
            candidates.distinctBy { it.kind to it.title.id }.filter {
                it.kind == parsed.kind &&
                    (
                        parsed.year == null ||
                            it.title
                                .date()
                                .take(4)
                                .toIntOrNull() == parsed.year
                    ) &&
                    if (parsed.explicitId != null) {
                        it.title.id == parsed.explicitId
                    } else {
                        (
                            parsed.year != null &&
                                it.title
                                    .date()
                                    .take(4)
                                    .toIntOrNull() == parsed.year &&
                                normalize(parsed.title) in listOf(normalize(it.title.displayTitle()), normalize(it.title.original()))
                        )
                    }
            }
        if (matching.size != 1) return null
        if (parsed.kind == "tv" && (parsed.season == null || parsed.episodes.isEmpty())) return null
        return matching.single()
    }

    fun rank(
        parsed: ParsedMediaName,
        candidates: List<MediaCandidate>
    ): List<MediaCandidate> =
        candidates.sortedByDescending {
            val title = normalize(it.title.displayTitle())
            val query = normalize(parsed.title)
            (
                if (title == query || normalize(it.title.original()) == query) {
                    10
                } else if (title.contains(query)) {
                    1
                } else {
                    0
                }
            ) +
                (if (parsed.year != null && it.title.date().startsWith(parsed.year.toString())) 3 else 0)
        }
}
