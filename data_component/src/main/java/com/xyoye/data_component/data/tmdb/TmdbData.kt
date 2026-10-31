package com.xyoye.data_component.data.tmdb

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TmdbPage(
    val page: Int = 1,
    val results: List<TmdbTitle> = emptyList(),
    @Json(name = "total_pages") val totalPages: Int = 1
)

@JsonClass(generateAdapter = true)
data class TmdbTitle(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    val overview: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "vote_average") val voteAverage: Double = 0.0,
    val genres: List<TmdbGenre> = emptyList(),
    val seasons: List<TmdbSeason> = emptyList()
) {
    fun displayTitle(): String = title?.takeIf { it.isNotBlank() } ?: name.orEmpty()

    fun original(): String = originalTitle ?: originalName.orEmpty()

    fun date(): String = releaseDate ?: firstAirDate.orEmpty()
}

@JsonClass(generateAdapter = true)
data class TmdbGenre(
    val id: Int,
    val name: String = ""
)

@JsonClass(generateAdapter = true)
data class TmdbSeason(
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "air_date") val airDate: String? = null,
    val episodes: List<TmdbEpisode> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbEpisode(
    val id: Long? = null,
    @Json(name = "episode_number") val episodeNumber: Int,
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @Json(name = "air_date") val airDate: String? = null,
    @Json(name = "still_path") val stillPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbConfiguration(
    val images: TmdbImageConfiguration
)

@JsonClass(generateAdapter = true)
data class TmdbImageConfiguration(
    @Json(name = "secure_base_url") val baseUrl: String,
    @Json(name = "poster_sizes") val posterSizes: List<String> = emptyList(),
    @Json(name = "backdrop_sizes") val backdropSizes: List<String> = emptyList(),
    @Json(name = "still_sizes") val stillSizes: List<String> = emptyList()
)
