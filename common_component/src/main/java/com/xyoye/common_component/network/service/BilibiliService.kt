package com.xyoye.common_component.network.service

import com.xyoye.common_component.network.config.HeaderKey
import com.xyoye.data_component.data.bilibili.BilibiliHistoryCursorData
import com.xyoye.data_component.data.bilibili.BilibiliJsonModel
import com.xyoye.data_component.data.bilibili.BilibiliNavData
import com.xyoye.data_component.data.bilibili.BilibiliPagelistItem
import com.xyoye.data_component.data.bilibili.BilibiliPlayurlData
import com.xyoye.data_component.data.bilibili.BilibiliQrcodeGenerateData
import com.xyoye.data_component.data.bilibili.BilibiliQrcodePollData
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

interface BilibiliService {
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

    @GET("/x/web-interface/history/cursor")
    suspend fun historyCursor(
        @Header(HeaderKey.BASE_URL) baseUrl: String,
        @QueryMap params: Map<String, @JvmSuppressWildcards Any>,
    ): BilibiliJsonModel<BilibiliHistoryCursorData>

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

