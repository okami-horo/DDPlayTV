package com.xyoye.common_component.network.service

import com.xyoye.common_component.network.config.HeaderKey
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanFileMetasResponse
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanUinfoResponse
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanXpanListResponse
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanXpanSearchResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface BaiduPanService {
    @GET("/oauth/2.0/device/code")
    suspend fun oauthDeviceCode(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Query("response_type") responseType: String,
        @Query("client_id") clientId: String,
        @Query("scope") scope: String
    ): Response<ResponseBody>

    @GET("/oauth/2.0/token")
    suspend fun oauthToken(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Query("grant_type") grantType: String,
        @Query("code") code: String?,
        @Query("refresh_token") refreshToken: String?,
        @Query("client_id") clientId: String,
        @Query("client_secret") clientSecret: String
    ): Response<ResponseBody>

    @GET("/rest/2.0/xpan/nas")
    suspend fun xpanUinfo(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Query("method") method: String,
        @Query("access_token") accessToken: String,
        @Query("vip_version") vipVersion: String?
    ): BaiduPanUinfoResponse

    @GET("/rest/2.0/xpan/file")
    suspend fun xpanFileList(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Query("method") method: String,
        @Query("access_token") accessToken: String,
        @Query("dir") dir: String?,
        @Query("order") order: String?,
        @Query("desc") desc: Int?,
        @Query("start") start: Int?,
        @Query("limit") limit: Int?,
        @Query("web") web: Int?
    ): BaiduPanXpanListResponse

    @GET("/rest/2.0/xpan/file")
    suspend fun xpanFileSearch(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Query("method") method: String,
        @Query("access_token") accessToken: String,
        @Query("dir") dir: String?,
        @Query("key") key: String,
        @Query("recursion") recursion: Int?,
        @Query("category") category: Int?,
        @Query("web") web: Int?
    ): BaiduPanXpanSearchResponse

    @GET("/rest/2.0/xpan/multimedia")
    suspend fun xpanFileMetas(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Query("method") method: String,
        @Query("access_token") accessToken: String,
        @Query("fsids") fsids: String,
        @Query("dlink") dlink: Int?,
        @Query("needmedia") needmedia: Int?,
        @Query("detail") detail: Int?
    ): BaiduPanFileMetasResponse
}
