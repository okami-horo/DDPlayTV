package com.xyoye.common_component.metadata

data class ParsedMediaName(
    val title: String,
    val year: Int?,
    val kind: String?,
    val season: Int?,
    val episodes: List<Int>,
    val absoluteEpisode: Int?,
    val explicitId: Long?,
    val extra: Boolean
)

object MediaNameParser {
    private val episode = Regex("(?i)\\bS(\\d{1,2})E(\\d{1,3})(?:[- ]?E(\\d{1,3}))?")
    private val id = Regex("(?i)[\\[{]tmdb(?:[-:= ](movie|tv))?[-:= ](\\d+)[\\]}]")
    private val year = Regex("(?:[ (\\[])(19\\d{2}|20\\d{2})(?=[ )\\]]|$)")
    private val tags =
        Regex(
            "(?i)\\b(?:2160p|1080[pi]|720p|480p|4k|x26[45]|h[ .]?26[45]|hevc|avc|av1|webrip|web[ .-]?dl|bluray|bdrip|aac|dts|flac|10bit|8bit)\\b"
        )

    fun parse(
        fileName: String,
        parent: String = ""
    ): ParsedMediaName {
        var text = fileName.replace(Regex("(?i)\\.(mkv|mp4|avi|mov|wmv|ts|m2ts|webm)$"), "")
        val idMatch = id.find(text)
        val seasonMatch = episode.find(text)
        val absolute = Regex("\\s-\\s(\\d{1,3})(?:v\\d)?(?=\\s|$)").find(text)
        val extra = Regex("(?i)\\b(NCOP|NCED|PV|trailer|sample)\\b").containsMatchIn(text)
        val special = Regex("(?i)\\b(OVA|OAD|SP|special)\\b").containsMatchIn(text)
        if (seasonMatch != null || absolute != null) text = text.replace(Regex("^\\[[^]]+]\\s*"), "")
        text = id.replace(text, " ")
        text = episode.replace(text, " ")
        text = text.replace(Regex("\\s-\\s\\d{1,3}(?:v\\d)?(?=\\s|$)"), " ")
        text = text.replace('.', ' ').replace('_', ' ')
        val yearMatch = year.find(text)
        val parsedYear = yearMatch?.groupValues?.get(1)?.toIntOrNull()
        if (yearMatch != null) text = text.replaceRange(yearMatch.range, " ")
        text = tags.replace(text, " ").replace(Regex("[\\[(]\\s*[\\])]"), " ")
        text =
            text
                .replace(Regex("(?i)\\b(OVA|OAD|SP|special|NCOP|NCED)\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '-', '[', ']', '(', ')')
        if (text.isBlank()) text = parent.substringAfterLast('/').replace('.', ' ')
        val first = seasonMatch?.groupValues?.get(2)?.toIntOrNull()
        val last = seasonMatch?.groupValues?.get(3)?.toIntOrNull() ?: first
        val episodes = if (first != null && last != null && last >= first && last - first < 30) (first..last).toList() else emptyList()
        val explicitKind =
            idMatch
                ?.groupValues
                ?.get(1)
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
        return ParsedMediaName(
            text,
            parsedYear,
            explicitKind ?: if (seasonMatch != null || absolute != null || special) "tv" else null,
            seasonMatch?.groupValues?.get(1)?.toIntOrNull(),
            episodes,
            absolute?.groupValues?.get(1)?.toIntOrNull(),
            idMatch?.groupValues?.get(2)?.toLongOrNull(),
            extra
        )
    }
}
