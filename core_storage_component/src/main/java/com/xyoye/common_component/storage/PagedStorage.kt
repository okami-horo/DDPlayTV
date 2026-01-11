package com.xyoye.common_component.storage

import com.xyoye.common_component.storage.file.StorageFile

/**
 * 可分页媒体库抽象。
 *
 * 约定：
 * - 分页能力仅对“当前目录”生效（由具体 Storage 自行决定哪些目录可分页）
 * - UI 可通过 [state]/[hasMore] 决定是否展示“加载更多/重试/无更多”入口
 */
interface PagedStorage {
    enum class State {
        IDLE,
        LOADING,
        ERROR,
        NO_MORE
    }

    var state: State

    fun hasMore(): Boolean

    suspend fun reset()

    suspend fun loadMore(): Result<List<StorageFile>>
}
