package com.xyoye.data_component.bean

/**
 * 弹幕轨道资源类型。
 *
 * - [LocalFile]：本地弹幕文件（点播/缓存等）
 * - [BilibiliLive]：Bilibili 直播实时弹幕（WSS）
 */
sealed interface DanmuTrackResource {
    data class LocalFile(
        val danmu: LocalDanmuBean
    ) : DanmuTrackResource

    data class BilibiliLive(
        val storageKey: String,
        val roomId: Long
    ) : DanmuTrackResource
}
