package com.xyoye.common_component.network.repository

import com.xyoye.common_component.network.RetrofitManager

/**
 * Created by xyoye on 2024/1/6.
 */

object ResourceRepository : BaseRepository() {
    /**
     * 匹配弹幕
     */
    suspend fun matchDanmu(
        fileHash: String,
        fileName: String,
        fileSize: Long,
        videoDuration: Int = 0,
        matchMode: String = "hashAndFileName"
    ) =
        request()
            .param("fileHash", fileHash)
            .param("fileName", fileName)
            .param("fileSize", fileSize)
            .param("videoDuration", videoDuration)
            .param("matchMode", matchMode)
            .doPost {
                RetrofitManager.danDanService.matchDanmu(it)
            }

    /**
     * 搜索弹幕
     */
    suspend fun searchDanmu(anime: String) =
        request()
            .param("anime", anime)
            .doGet {
                RetrofitManager.danDanService.searchDanmu(it)
            }

    /**
     * 获取弹幕内容
     */
    suspend fun getDanmuContent(
        episodeId: String,
        withRelated: Boolean = true
    ) = request()
        .param("withRelated", withRelated)
        .doGet {
            RetrofitManager.danDanService.getDanmuContent(episodeId, it)
        }

    /**
     * 获取第三方弹幕
     */
    suspend fun getRelatedDanmu(episodeId: String) =
        request()
            .doGet {
                RetrofitManager.danDanService.getRelatedDanmu(episodeId)
            }

    /**
     * 获取第三方弹幕内容
     */
    suspend fun getRelatedDanmuContent(url: String) =
        request()
            .param("url", url)
            .doGet {
                RetrofitManager.danDanService.getRelatedDanmuContent(it)
            }

    /**
     * 发送一条弹幕
     */
    suspend fun sendOneDanmu(
        episodeId: String,
        time: String,
        mode: Int,
        color: Int,
        comment: String
    ) = request()
        .param("time", time)
        .param("mode", mode)
        .param("color", color)
        .param("comment", comment)
        .doPost {
            RetrofitManager.danDanService.sendOneDanmu(episodeId, it)
        }

    /**
     * 匹配字幕，Thunder
     */
    suspend fun matchSubtitleFormThunder(hash: String) =
        request()
            .doGet {
                RetrofitManager.extendedService.matchSubtitleFormThunder(hash)
            }

    /**
     * 匹配字幕，Shooter
     */
    suspend fun matchSubtitleFormShooter(
        fileHash: String,
        fileName: String
    ) = request()
        .param("filehash", fileHash)
        .param("pathinfo", fileName)
        .param("format", "json")
        .param("lang", "Chn")
        .doPost {
            RetrofitManager.extendedService.matchSubtitleFormShooter(it)
        }

    /**
     * 搜索字幕
     */
    suspend fun searchSubtitle(
        token: String,
        keyword: String,
        page: Int
    ) = request()
        .param("token", token)
        .param("q", keyword)
        .param("pos", page)
        .doGet {
            RetrofitManager.extendedService.searchSubtitle(it)
        }

    /**
     * 字幕详情
     */
    suspend fun getSubtitleDetail(
        token: String,
        id: String
    ) = request()
        .param("token", token)
        .param("id", id)
        .doGet {
            RetrofitManager.extendedService.searchSubtitleDetail(it)
        }

    /**
     * 获取资源响应
     */
    suspend fun getResourceResponse(
        url: String,
        headers: Map<String, String> = emptyMap()
    ) = request()
        .doGet {
            RetrofitManager.extendedService.getResourceResponse(url, headers)
        }

    /**
     * 获取资源响应正文
     */
    suspend fun getResourceResponseBody(
        url: String,
        headers: Map<String, String> = emptyMap()
    ) = request()
        .doGet {
            RetrofitManager.extendedService.getResourceResponseBody(url, headers)
        }
}
