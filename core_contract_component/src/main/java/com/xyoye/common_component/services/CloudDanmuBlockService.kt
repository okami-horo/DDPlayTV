package com.xyoye.common_component.services

import com.alibaba.android.arouter.facade.template.IProvider

/**
 * 云端屏蔽词同步能力（弹幕/播放器域）
 *
 * 壳层通过该 Service 解耦对 network/db 的直接依赖。
 */
interface CloudDanmuBlockService : IProvider {
    /**
     * 根据更新周期判断是否需要同步；内部负责持久化与更新时间戳。
     */
    suspend fun syncIfNeed()
}
