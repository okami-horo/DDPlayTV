package com.xyoye.common_component.utils.danmu

import com.xyoye.common_component.bilibili.BilibiliKeys
import com.xyoye.common_component.bilibili.danmaku.BilibiliDanmakuDownloader
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.bean.DanmuTrackResource
import com.xyoye.data_component.enums.MediaType

object StorageDanmuMatcher {
    fun isBilibiliLive(source: BaseVideoSource): Boolean {
        if (source.getMediaType() != MediaType.BILIBILI_STORAGE) return false
        return BilibiliKeys.parse(source.getUniqueKey()) is BilibiliKeys.LiveKey
    }

    suspend fun matchDanmu(source: BaseVideoSource): DanmuTrackResource? {
        if (source.getMediaType() != MediaType.BILIBILI_STORAGE) return null

        val parsed = BilibiliKeys.parse(source.getUniqueKey()) ?: return null
        val cid =
            when (parsed) {
                is BilibiliKeys.ArchiveKey -> parsed.cid
                is BilibiliKeys.PgcEpisodeKey -> parsed.cid
                else -> null
            }
        val roomId = (parsed as? BilibiliKeys.LiveKey)?.roomId

        if (cid == null && roomId == null) {
            return null
        }

        val library = DatabaseManager.instance.getMediaLibraryDao().getById(source.getStorageId()) ?: return null
        val storageKey = storageKey(library.mediaType.value, library.url)

        if (cid != null) {
            val danmu = BilibiliDanmakuDownloader.getOrDownload(storageKey, cid) ?: return null
            return DanmuTrackResource.LocalFile(danmu)
        }

        if (roomId != null) {
            return DanmuTrackResource.BilibiliLive(
                storageKey = storageKey,
                roomId = roomId,
            )
        }

        return null
    }

    private fun storageKey(
        mediaTypeValue: String,
        libraryUrl: String
    ): String = "$mediaTypeValue:${libraryUrl.trim().removeSuffix("/")}"
}
