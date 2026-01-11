package com.xyoye.common_component.source.factory

import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.resolver.PlaybackProfileResolver
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageSortOption
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.LinkStorage
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.enums.PlayerType

/**
 * Created by xyoye on 2023/1/2.
 */

object StorageVideoSourceFactory {
    suspend fun create(file: StorageFile): StorageVideoSource? {
        val profile = resolvePlaybackProfile(file.storage)
        return create(file, profile)
    }

    suspend fun create(
        file: StorageFile,
        profile: PlaybackProfile
    ): StorageVideoSource? {
        val storage = file.storage
        val videoSources = getVideoSources(storage)
        val playUrl = storage.createPlayUrl(file, profile) ?: return null
        val danmu = findLocalDanmu(file, storage)
        val subtitlePath = getSubtitlePath(file, storage)
        val audioPath = file.playHistory?.audioPath
        return StorageVideoSource(
            playUrl,
            file,
            videoSources,
            danmu,
            subtitlePath,
            audioPath,
            profile,
        )
    }

    private fun resolvePlaybackProfile(storage: Storage): PlaybackProfile {
        val globalPlayerType = PlayerType.valueOf(PlayerConfig.getUsePlayerType())
        val resolvedLibrary = storage.library.takeIf { storage !is LinkStorage }
        return PlaybackProfileResolver.resolve(
            library = resolvedLibrary,
            globalPlayerType = globalPlayerType,
            mediaType = storage.library.mediaType,
            supportedPlayerTypes = storage.supportedPlayerTypes(),
            preferredPlayerType = storage.preferredPlayerType(),
        )
    }

    private suspend fun findLocalDanmu(
        file: StorageFile,
        storage: Storage
    ): LocalDanmuBean? {
        // 从播放记录读取弹幕
        val history = file.playHistory
        if (history?.danmuPath?.isNotEmpty() == true) {
            return LocalDanmuBean(history.danmuPath!!, history.episodeId)
        }

        // 是否匹配同文件夹内同名弹幕
        if (DanmuConfig.isAutoLoadSameNameDanmu()) {
            return storage.cacheDanmu(file)
        }

        return null
    }

    private suspend fun getSubtitlePath(
        file: StorageFile,
        storage: Storage
    ): String? {
        val subtitleNotFound = null

        // 从播放记录读取弹幕
        if (file.playHistory?.subtitlePath?.isNotEmpty() == true) {
            return file.playHistory?.subtitlePath
        }

        // 是否匹配同文件夹内同名字幕
        if (SubtitleConfig.isAutoLoadSameNameSubtitle()) {
            return storage.cacheSubtitle(file)
                ?: subtitleNotFound
        }

        return subtitleNotFound
    }

    private fun getVideoSources(storage: Storage): List<StorageFile> =
        storage.directoryFiles
            .filter { it.isVideoFile() }
            .filter { AppConfig.isShowHiddenFile() || !it.fileName().startsWith(".") }
            .sortedWith(StorageSortOption.comparator())
}
