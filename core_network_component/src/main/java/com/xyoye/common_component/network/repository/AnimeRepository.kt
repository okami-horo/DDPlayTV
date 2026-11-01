package com.xyoye.common_component.network.repository

import com.xyoye.common_component.network.RetrofitManager

/**
 * Created by xyoye on 2024/1/5
 */

object AnimeRepository : BaseRepository() {
    /**
     * 搜索番剧，根据标签
     */
    suspend fun searchAnimeByTag(tags: List<String>) =
        request()
            .param("tags", tags.joinToString(","))
            .doGet {
                RetrofitManager.danDanService.searchAnimeByTag(it)
            }

    /**
     * 获取番剧详情
     */
    suspend fun getAnimeDetail(animeId: String) =
        request()
            .doGet {
                RetrofitManager.danDanService.getAnimeDetail(animeId)
            }

    /**
     * 收藏番剧
     */
    suspend fun followAnime(animeId: String) =
        request()
            .param("animeId", animeId)
            .param("favoriteStatus", "favorited")
            .param("rating", "0")
            .doPost {
                RetrofitManager.danDanService.followAnime(it)
            }

    /**
     * 取消收藏番剧
     */
    suspend fun cancelFollowAnime(animeId: String) =
        request()
            .doDelete {
                RetrofitManager.danDanService.cancelFollowAnime(animeId)
            }

    /**
     * 获取已收藏的番剧
     */
    suspend fun getFollowedAnime() =
        request()
            .param("onlyOnAir", false)
            .doGet {
                RetrofitManager.danDanService.getFollowedAnime(it)
            }

    /**
     * 获取番剧播放历史
     */
    suspend fun getPlayHistory() =
        request()
            .doGet {
                RetrofitManager.danDanService.getPlayHistory()
            }

    /**
     * 添加剧集播放历史
     */
    suspend fun addEpisodePlayHistory(episodeIds: List<String>) =
        request()
            .param("episodeIdList", episodeIds)
            .doPost {
                RetrofitManager.danDanService.addPlayHistory(it)
            }
}
