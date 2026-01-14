package com.xyoye.common_component.network.service

import com.xyoye.common_component.network.config.HeaderKey
import com.xyoye.data_component.data.open115.Open115DownUrlResponse
import com.xyoye.data_component.data.open115.Open115ListFilesResponse
import com.xyoye.data_component.data.open115.Open115ProApiEnvelope
import com.xyoye.data_component.data.open115.Open115RefreshTokenResponse
import com.xyoye.data_component.data.open115.Open115SearchResponse
import com.xyoye.data_component.data.open115.Open115UserInfoResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface Open115Service {
    @GET("/open/user/info")
    suspend fun userInfo(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Header(HeaderKey.AUTHORIZATION) authorization: String
    ): Open115UserInfoResponse

    @GET("/open/ufile/files")
    suspend fun listFiles(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Header(HeaderKey.AUTHORIZATION) authorization: String,
        @Query("cid") cid: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("asc") asc: String? = null,
        @Query("o") order: String? = null,
        @Query("show_dir") showDir: String? = null
    ): Open115ListFilesResponse

    @GET("/open/ufile/search")
    suspend fun searchFiles(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Header(HeaderKey.AUTHORIZATION) authorization: String,
        @Query("search_value") searchValue: String,
        @Query("cid") cid: String? = null,
        @Query("type") type: String? = null,
        @Query("fc") fc: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Open115SearchResponse

    @FormUrlEncoded
    @POST("/open/ufile/downurl")
    suspend fun downUrl(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Header(HeaderKey.AUTHORIZATION) authorization: String,
        @Header("User-Agent") userAgent: String,
        @Field("pick_code") pickCode: String
    ): Open115DownUrlResponse

    @FormUrlEncoded
    @POST("/open/refreshToken")
    suspend fun refreshToken(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Field("refresh_token") refreshToken: String
    ): Open115RefreshTokenResponse

    @GET("/open/folder/get_info")
    suspend fun folderGetInfo(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Header(HeaderKey.AUTHORIZATION) authorization: String,
        @Query("file_id") fileId: String
    ): Open115ProApiEnvelope
}

