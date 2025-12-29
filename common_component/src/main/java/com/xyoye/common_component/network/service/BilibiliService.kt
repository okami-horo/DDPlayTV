package com.xyoye.common_component.network.service

import com.xyoye.common_component.network.config.HeaderKey
import com.xyoye.data_component.data.bilibili.BilibiliCookieInfoData
import com.xyoye.data_component.data.bilibili.BilibiliCookieRefreshData
import com.xyoye.data_component.data.bilibili.BilibiliHistoryCursorData
import com.xyoye.data_component.data.bilibili.BilibiliJsonModel
import com.xyoye.data_component.data.bilibili.BilibiliLiveDanmuInfoData
import com.xyoye.data_component.data.bilibili.BilibiliLivePlayUrlData
import com.xyoye.data_component.data.bilibili.BilibiliLiveRoomInfoData
import com.xyoye.data_component.data.bilibili.BilibiliNavData
import com.xyoye.data_component.data.bilibili.BilibiliPagelistItem
import com.xyoye.data_component.data.bilibili.BilibiliPlayurlData
import com.xyoye.data_component.data.bilibili.BilibiliQrcodeGenerateData
import com.xyoye.data_component.data.bilibili.BilibiliQrcodePollData
import okhttp3.ResponseBody
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

interface BilibiliService {
    @GET("/")
    suspend fun preheat(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
    ): ResponseBody

    @GET("/x/web-interface/nav")
    suspend fun nav(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
    ): BilibiliJsonModel<BilibiliNavData>

    @GET("/x/passport-login/web/qrcode/generate")
    suspend fun qrcodeGenerate(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
    ): BilibiliJsonModel<BilibiliQrcodeGenerateData>

    @GET("/x/passport-login/web/qrcode/poll")
    suspend fun qrcodePoll(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Query("qrcode_key") qrcodeKey: String,
    ): BilibiliJsonModel<BilibiliQrcodePollData>

    @GET("/x/passport-login/web/cookie/info")
    suspend fun cookieInfo(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Query("csrf") csrf: String? = null,
    ): BilibiliJsonModel<BilibiliCookieInfoData>

    @GET("/correspond/1/{path}")
    suspend fun correspond(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Path("path") correspondPath: String,
    ): ResponseBody

    @FormUrlEncoded
    @POST("/x/passport-login/web/cookie/refresh")
    suspend fun cookieRefresh(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @FieldMap params: Map<String, @JvmSuppressWildcards Any>,
    ): BilibiliJsonModel<BilibiliCookieRefreshData>

    @FormUrlEncoded
    @POST("/x/passport-login/web/confirm/refresh")
    suspend fun confirmRefresh(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @FieldMap params: Map<String, @JvmSuppressWildcards Any>,
    ): BilibiliJsonModel<Any>

    @GET("/x/web-interface/history/cursor")
    suspend fun historyCursor(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @QueryMap params: Map<String, @JvmSuppressWildcards Any>,
    ): BilibiliJsonModel<BilibiliHistoryCursorData>

    @GET("/room/v1/Room/get_info")
    suspend fun liveRoomInfo(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Query("room_id") roomId: Long,
    ): BilibiliJsonModel<BilibiliLiveRoomInfoData>

    @GET("/room/v1/Room/playUrl")
    suspend fun livePlayUrl(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @QueryMap params: Map<String, @JvmSuppressWildcards Any>,
    ): BilibiliJsonModel<BilibiliLivePlayUrlData>

    @GET("/xlive/web-room/v1/index/getDanmuInfo")
    suspend fun liveDanmuInfo(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @QueryMap params: Map<String, @JvmSuppressWildcards Any>,
    ): BilibiliJsonModel<BilibiliLiveDanmuInfoData>

    @GET("/x/player/pagelist")
    suspend fun pagelist(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Query("bvid") bvid: String,
    ): BilibiliJsonModel<List<BilibiliPagelistItem>>

    @GET("/x/player/wbi/playurl")
    suspend fun playurl(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @QueryMap params: Map<String, @JvmSuppressWildcards Any>,
    ): BilibiliJsonModel<BilibiliPlayurlData>

    @GET("/{cid}.xml")
    suspend fun danmakuXml(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Path("cid") cid: Long,
    ): ResponseBody

    @GET("/x/v1/dm/list.so")
    suspend fun danmakuListSo(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @Query("oid") oid: Long,
    ): ResponseBody
}
