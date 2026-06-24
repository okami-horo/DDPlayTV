package com.xyoye.data_component.data

data class MatchResult(
    val isMatched: Boolean,
    val episodes: List<DanmuEpisodeData>,
    val matchMethod: MatchMethod,
)

enum class MatchMethod {
    HASH,
    FILE_NAME,
    SEARCH_FALLBACK,
}
