package com.xyoye.common_component.utils.danmu

import com.xyoye.common_component.utils.danmu.query.DanmuQuery
import com.xyoye.common_component.utils.danmu.source.DanmuSource
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.data.DanmuAnimeData
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.data.MatchResult
import java.io.InputStream

/**
 * Created by xyoye on 2024/1/14.
 */

interface DanmuFinder {
    companion object {
        val instance: DanmuFinder by lazy {
            DanmuFinderImpl(DanmuQuery.instance)
        }
    }

    /**
     * 获取弹幕匹配数据
     */
    suspend fun getMatched(source: DanmuSource): MatchResult?

    /**
     * 获取弹幕匹配数据，并下载
     */
    suspend fun downloadMatched(source: DanmuSource): LocalDanmuBean?

    /**
     * 下载剧集弹幕，默认包含关联的第三方弹幕
     */
    suspend fun downloadEpisode(
        episode: DanmuEpisodeData,
        withRelated: Boolean = true
    ): LocalDanmuBean?

    /**
     * 搜索弹幕
     */
    suspend fun search(text: String): List<DanmuAnimeData>

    suspend fun searchResult(text: String): Result<List<DanmuAnimeData>> = Result.success(search(text))

    /**
     * 保存剧集对应的字幕流数据
     */
    suspend fun saveStream(
        episode: DanmuEpisodeData,
        inputStream: InputStream
    ): LocalDanmuBean?
}
