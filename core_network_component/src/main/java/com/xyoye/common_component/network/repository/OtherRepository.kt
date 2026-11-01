package com.xyoye.common_component.network.repository

import com.xyoye.common_component.network.RetrofitManager

/**
 * Created by xyoye on 2024/1/6.
 */

object OtherRepository : BaseRepository() {
    /**
     * 获取云屏蔽数据
     */
    suspend fun getCloudFilters() =
        request()
            .doGet {
                RetrofitManager.danDanService.getCloudFilters()
            }

    /**
     * 获取分词结果
     */
    suspend fun getSegmentWords(text: String) =
        request()
            .param("text", text)
            .param("tasks", listOf("tok"))
            .doPost {
                RetrofitManager.extendedService.segmentWords(it)
            }
}
