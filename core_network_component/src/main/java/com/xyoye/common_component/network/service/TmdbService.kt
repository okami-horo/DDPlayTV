package com.xyoye.common_component.network.service

import com.xyoye.data_component.data.tmdb.TmdbConfiguration
import com.xyoye.data_component.data.tmdb.TmdbPage
import com.xyoye.data_component.data.tmdb.TmdbSeason
import com.xyoye.data_component.data.tmdb.TmdbTitle
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbService {
    @GET("configuration")
    suspend fun configuration(): TmdbConfiguration

    @GET("search/{kind}")
    suspend fun search(
        @Path("kind") kind: String,
        @Query("query") query: String,
        @Query("language") language: String,
        @Query("page") page: Int,
        @Query("year") movieYear: Int?,
        @Query("first_air_date_year") seriesYear: Int?,
        @Query("include_adult") includeAdult: Boolean = false
    ): TmdbPage

    @GET("{kind}/{id}")
    suspend fun details(
        @Path("kind") kind: String,
        @Path("id") id: Long,
        @Query("language") language: String
    ): TmdbTitle

    @GET("tv/{id}/season/{season}")
    suspend fun season(
        @Path("id") id: Long,
        @Path("season") season: Int,
        @Query("language") language: String
    ): TmdbSeason
}
