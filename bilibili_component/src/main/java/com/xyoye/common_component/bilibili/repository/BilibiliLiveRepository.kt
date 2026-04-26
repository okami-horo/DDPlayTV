package com.xyoye.common_component.bilibili.repository

import com.xyoye.data_component.data.bilibili.BilibiliLiveDanmuConnectInfo
import com.xyoye.data_component.data.bilibili.BilibiliLiveFollowData
import com.xyoye.data_component.data.bilibili.BilibiliLivePlayUrlData
import com.xyoye.data_component.data.bilibili.BilibiliLiveRoomInfoData

class BilibiliLiveRepository internal constructor(
    private val core: BilibiliRepositoryCore
) {
    suspend fun liveFollow(
        page: Int,
        pageSize: Int = 9,
        ignoreRecord: Int = 1,
        hitAb: Boolean = true
    ): Result<BilibiliLiveFollowData> =
        core.liveFollow(
            page = page,
            pageSize = pageSize,
            ignoreRecord = ignoreRecord,
            hitAb = hitAb,
        )

    suspend fun liveRoomInfo(roomId: Long): Result<BilibiliLiveRoomInfoData> = core.liveRoomInfo(roomId)

    suspend fun livePlayUrl(
        roomId: Long,
        platform: String = "h5",
        qn: Int? = 10000
    ): Result<BilibiliLivePlayUrlData> = core.livePlayUrl(roomId, platform, qn)

    suspend fun liveDanmuInfo(roomId: Long): Result<BilibiliLiveDanmuConnectInfo> = core.liveDanmuInfo(roomId)
}
