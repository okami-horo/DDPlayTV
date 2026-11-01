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
        if (parsed.extra || parsed.absoluteEpisode != null || parsed.title.isBlank()) return null
        val eligible =
            candidates.distinctBy { it.kind to it.title.id }.filter {
                (parsed.kind == null || it.kind == parsed.kind) &&
                    (
                        parsed.year == null ||
                            it.title
                                .date()
                                .take(4)
                                .toIntOrNull() == parsed.year
                    )
            }
        val title = normalize(parsed.title)
        val matching =
            eligible.filter {
                if (parsed.explicitId != null) {
                    it.title.id == parsed.explicitId
                } else {
                    title in listOf(normalize(it.title.displayTitle()), normalize(it.title.original()))
                }
            }
        val match =
            matching.singleOrNull() ?: eligible.singleOrNull()?.takeIf { candidate ->
                // Long series names may omit a subtitle; short fragments and numbered sequels stay unresolved.
                matching.isEmpty() &&
                    parsed.explicitId == null &&
                    parsed.kind == "tv" &&
                    title.length >= 8 &&
                    listOf(candidate.title.displayTitle(), candidate.title.original()).any {
                        val full = normalize(it)
                        full.startsWith(title) && full.length > title.length && full.removePrefix(title).none(Char::isDigit)
                    }
            } ?: return null
        if (match.kind == "tv" && (parsed.season == null || parsed.episodes.isEmpty())) return null
        return match
    }
}
