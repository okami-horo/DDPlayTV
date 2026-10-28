package com.xyoye.common_component.source.factory

import android.net.Uri
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.playback.addon.PlaybackAddon
import com.xyoye.common_component.playback.addon.PlaybackReleasableAddon
import com.xyoye.common_component.playback.addon.PlaybackReplacementIntent
import com.xyoye.common_component.resolver.PlaybackProfileResolver
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.LinkStorage
import com.xyoye.common_component.storage.transport.AcquiredPlaybackTransport
import com.xyoye.common_component.storage.transport.PlaybackTransportFactory
import com.xyoye.common_component.storage.transport.PlaybackTransportLease
import com.xyoye.common_component.storage.transport.TransportAcquisitionResult
import com.xyoye.common_component.storage.transport.releaseTransportResources
import com.xyoye.common_component.utils.getDirPath
import com.xyoye.common_component.utils.isFileExist
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Created by xyoye on 2023/1/2.
 */

object StorageVideoSourceFactory {
    private const val LOG_TAG = "StorageVideoSourceFactory"

    suspend fun create(
        file: StorageFile,
        profile: PlaybackProfile,
        queue: List<StorageFile>,
        releaseResolution: () -> Unit = {},
        replacement: PlaybackReplacementIntent? = null
    ): StorageVideoSource? =
        createOwned(
            file,
            profile,
            queue,
            acquire = { input, selected -> PlaybackTransportFactory().acquire(input.storage, input, selected, replacement) },
            prepareExtras = { playbackFile ->
                val storage = playbackFile.storage
                SourceExtras(
                    findLocalDanmu(playbackFile, storage),
                    getSubtitlePath(playbackFile, storage),
                    playbackFile.playHistory?.audioPath,
                    storage.getNetworkHeaders(playbackFile)?.toMap()
                )
            },
            createAddon = { transport, _ ->
                StorageVideoSource.createDefaultPlaybackAddon(
                    transport.lease.playUrl,
                    transport.playbackFile,
                    transport.lease
                )
            },
            releaseResolution = releaseResolution
        )

    internal data class SourceExtras(
        val danmu: LocalDanmuBean? = null,
        val subtitlePath: String? = null,
        val audioPath: String? = null,
        val httpHeaders: Map<String, String>? = null
    )

    internal suspend fun createOwned(
        file: StorageFile,
        profile: PlaybackProfile,
        queue: List<StorageFile>,
        acquire: suspend (StorageFile, PlaybackProfile) -> TransportAcquisitionResult,
        prepareExtras: suspend (StorageFile) -> SourceExtras,
        createAddon: (AcquiredPlaybackTransport, PlaybackProfile) -> PlaybackAddon?,
        releaseResolution: () -> Unit = {}
    ): StorageVideoSource? {
        // Queue entries are metadata for later resolution, never the owned playback connection.
        val inputKey = file.uniqueKey()
        val videoSources =
            queue
                .takeIf { items -> items.any { it.uniqueKey() == inputKey } }
                .orEmpty()
                .ifEmpty { listOf(file) }
                .map { original -> original.clone().apply { playHistory = original.playHistory?.copy() } }
        var lease: PlaybackTransportLease? = null
        var addon: PlaybackAddon? = null
        var source: StorageVideoSource? = null
        var delivered = false
        try {
            val transport =
                when (val result = acquire(file, profile)) {
                    is TransportAcquisitionResult.Success -> result.transport
                    is TransportAcquisitionResult.Failure -> {
                        result.cause?.let { throw it }
                        if ((file.storage as? com.xyoye.common_component.storage.AbstractStorage)?.throwPlaybackErrors == true) {
                            throw com.xyoye.common_component.storage.transport
                                .TransportAcquisitionException(result.reason)
                        }
                        return null
                    }
                }
            lease = transport.lease
            val extras = prepareExtras(transport.playbackFile)
            currentCoroutineContext().ensureActive()
            addon = createAddon(transport, profile)
            source =
                StorageVideoSource(
                    playUrl = lease.playUrl,
                    file = transport.playbackFile,
                    videoSources = videoSources,
                    danmu = extras.danmu,
                    subtitlePath = extras.subtitlePath,
                    audioPath = extras.audioPath,
                    playbackProfile = profile,
                    transportLease = lease,
                    playbackAddon = addon,
                    httpHeaders = extras.httpHeaders?.toMap(),
                    releaseResolution = releaseResolution
                )
            currentCoroutineContext().ensureActive()
            delivered = true
            return source
        } finally {
            if (!delivered) {
                withContext(NonCancellable + Dispatchers.IO) {
                    val constructed = source
                    if (constructed != null) {
                        constructed.releasePlaybackResources()
                    } else {
                        releaseTransportResources(
                            lease?.leaseId.orEmpty(),
                            listOf(
                                {
                                    (addon as? PlaybackReleasableAddon)?.onRelease()
                                    Unit
                                },
                                {
                                    lease?.release()
                                    Unit
                                }
                            )
                        )
                    }
                }
            }
        }
    }

    internal fun resolvePlaybackProfile(storage: Storage): PlaybackProfile {
        val globalPlayerType = PlayerType.valueOf(PlayerConfig.getUsePlayerType())
        val resolvedLibrary = storage.library.takeIf { storage !is LinkStorage }
        return PlaybackProfileResolver.resolve(
            library = resolvedLibrary,
            globalPlayerType = globalPlayerType,
            mediaType = storage.library.mediaType,
            supportedPlayerTypes = storage.supportedPlayerTypes(),
            preferredPlayerType = storage.preferredPlayerType()
        )
    }

    private suspend fun findLocalDanmu(
        file: StorageFile,
        storage: Storage
    ): LocalDanmuBean? {
        // 从播放记录读取弹幕
        val history = file.playHistory
        val historyDanmuPath = history?.danmuPath?.takeIf { it.isNotBlank() }
        if (historyDanmuPath != null) {
            if (isFileExist(historyDanmuPath)) {
                return LocalDanmuBean(historyDanmuPath, history.episodeId)
            }
            LogFacade.w(
                LogModule.STORAGE,
                LOG_TAG,
                "history danmu path missing, try recover from storage",
                mapOf(
                    "storageId" to storage.library.id.toString(),
                    "mediaType" to storage.library.mediaType.name,
                    "uniqueKey" to file.uniqueKey(),
                    "filePath" to runCatching { file.filePath() }.getOrNull().orEmpty(),
                    "danmuPath" to historyDanmuPath
                )
            )
        }

        // 是否匹配同文件夹内同名弹幕
        if (DanmuConfig.isAutoLoadSameNameDanmu()) {
            storage.cacheDanmu(file)?.let { return it }
            if (openParentDirectoryIfNeeded(file, storage)) {
                return storage.cacheDanmu(file)
            }
        }

        return null
    }

    private suspend fun getSubtitlePath(
        file: StorageFile,
        storage: Storage
    ): String? {
        val subtitleNotFound = null

        // 从播放记录读取字幕（若文件已不存在，则忽略并尝试重新匹配/缓存）
        val historySubtitlePath = file.playHistory?.subtitlePath?.takeIf { it.isNotBlank() }
        if (historySubtitlePath != null) {
            if (isFileExist(historySubtitlePath)) {
                return normalizeFileScheme(historySubtitlePath)
            }
            LogFacade.w(
                LogModule.STORAGE,
                LOG_TAG,
                "history subtitle path missing, try recover from storage",
                mapOf(
                    "storageId" to storage.library.id.toString(),
                    "mediaType" to storage.library.mediaType.name,
                    "uniqueKey" to file.uniqueKey(),
                    "filePath" to runCatching { file.filePath() }.getOrNull().orEmpty(),
                    "subtitlePath" to historySubtitlePath
                )
            )
        }

        // 是否匹配同文件夹内同名字幕
        if (SubtitleConfig.isAutoLoadSameNameSubtitle()) {
            storage.cacheSubtitle(file)?.let { return it }
            if (openParentDirectoryIfNeeded(file, storage)) {
                return storage.cacheSubtitle(file) ?: subtitleNotFound
            }
            return subtitleNotFound
        }

        return subtitleNotFound
    }

    private fun normalizeFileScheme(path: String): String {
        if (!path.startsWith("file://")) {
            return path
        }
        return Uri.parse(path).path ?: path
    }

    private suspend fun openParentDirectoryIfNeeded(
        file: StorageFile,
        storage: Storage
    ): Boolean {
        return runCatching {
            val filePath = file.filePath()
            val parentPath = getDirPath(filePath).ifBlank { "/" }
            val parentDir = storage.pathFile(parentPath, isDirectory = true) ?: return false
            storage.openDirectory(parentDir, refresh = false)
            true
        }.getOrDefault(false)
    }
}
