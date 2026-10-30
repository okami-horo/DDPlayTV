package com.xyoye.common_component.storage.impl

import android.net.Uri
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.network.repository.RemoteRepository
import com.xyoye.common_component.network.request.NetworkException
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.helper.LocalProxy
import com.xyoye.common_component.storage.file.helper.RemoteFileHelper
import com.xyoye.common_component.storage.file.impl.RemoteStorageFile
import com.xyoye.common_component.storage.transport.PlaybackTransportLease
import com.xyoye.common_component.storage.transport.PlaybackTransportProvider
import com.xyoye.common_component.storage.transport.directPlaybackTransport
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.danmu.DanmuFinder
import com.xyoye.common_component.utils.subtitle.SubtitleUtils
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.data.remote.RemoteVideoData
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.PlayerType
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Created by xyoye on 2023/4/1.
 */

class RemoteStorage internal constructor(
    library: MediaLibraryEntity,
    private val fetchFiles: suspend (RemoteStorage) -> Result<List<RemoteVideoData>>
) : AbstractStorage(library),
    PlaybackTransportProvider {
    constructor(library: MediaLibraryEntity) : this(library, { RemoteRepository.getStorageFiles(it) })

    private val groupByAnime = AppConfig.isRemoteAnimeGrouping()
    private var storageFilesSnapshot = listOf<RemoteVideoData>()
    private var resolvedTree: List<RemoteVideoData>? = null

    override var rootUri: Uri = generateRootUri()

    override suspend fun listFiles(file: StorageFile): List<StorageFile> =
        if (file.isRootFile()) {
            resolvedTree ?: getRemoteRootFiles() ?: emptyList()
        } else {
            (file as RemoteStorageFile).getRealFile().childData
        }.map { RemoteStorageFile(this, it) }

    override suspend fun getRootFile(): StorageFile {
        val videoData = RemoteVideoData(absolutePath = "/", isFolder = true)
        return RemoteStorageFile(this, videoData)
    }

    override suspend fun openFile(file: StorageFile): InputStream? = null

    override suspend fun pathFile(
        path: String,
        isDirectory: Boolean
    ): StorageFile? = resolvePlaybackReference(path, null, isDirectory)

    internal suspend fun resolvePlaybackReference(
        path: String,
        videoId: String?,
        isDirectory: Boolean
    ): StorageFile? {
        if (resolvedTree == null) {
            val files = fetchFiles(this).getOrThrow()
            storageFilesSnapshot = files
            resolvedTree =
                if (groupByAnime) {
                    RemoteFileHelper.convertGroupData(files)
                } else {
                    RemoteFileHelper.convertTreeData(files)
                }
        }
        if (isDirectory && path == "/") return getRootFile()

        fun find(items: List<RemoteVideoData>): RemoteVideoData? {
            for (item in items) {
                if (item.isFolder == isDirectory && item.absolutePath == path) return item
                find(item.childData)?.let { return it }
            }
            return null
        }
        val data =
            if (videoId != null && !isDirectory) {
                storageFilesSnapshot.firstOrNull { it.Id == videoId }
            } else {
                find(resolvedTree.orEmpty())
            }
        return data?.let { RemoteStorageFile(this, it) }
    }

    override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? {
        val videoId = Uri.parse(history.url).lastPathSegment ?: return null
        return resolvePlaybackReference(history.storagePath.orEmpty(), videoId, false)
            ?.also { it.playHistory = history }
    }

    override suspend fun acquirePlaybackTransport(
        file: StorageFile,
        profile: PlaybackProfile
    ): PlaybackTransportLease {
        val playerType = profile.playerType
        val fileUrl = (file as RemoteStorageFile).fileUrl()
        val token = library.remoteSecret ?: return directPlaybackTransport(fileUrl)

        val upstream =
            Uri
                .parse(fileUrl)
                .buildUpon()
                .appendQueryParameter("token", token)
                .build()
                .toString()

        val contentLength = runCatching { file.fileLength() }.getOrNull() ?: -1L
        val fileName = runCatching { file.fileName() }.getOrNull().orEmpty().ifEmpty { "video" }
        val (mode, interval, autoEnabled) =
            when (playerType) {
                PlayerType.TYPE_MPV_PLAYER ->
                    Triple(
                        PlayerConfig.getMpvLocalProxyMode(),
                        PlayerConfig.getMpvProxyRangeMinIntervalMs().toLong(),
                        false,
                    )
                else -> return directPlaybackTransport(upstream)
            }

        return LocalProxy.acquireIfNeeded(
            playerType = playerType,
            modeValue = mode,
            upstreamUrl = upstream,
            upstreamHeaders = getNetworkHeaders(),
            contentLength = contentLength,
            prePlayRangeMinIntervalMs = interval,
            fileName = fileName,
            autoEnabled = autoEnabled,
        )
    }

    override suspend fun cacheDanmu(file: StorageFile): LocalDanmuBean? {
        val videoData = (file as RemoteStorageFile).getRealFile()
        val episode =
            DanmuEpisodeData(
                episodeId = videoData.Id,
                episodeTitle = videoData.EpisodeTitle,
                animeId = videoData.AnimeId,
                animeTitle = videoData.AnimeTitle,
            )
        return DanmuFinder.instance.downloadEpisode(episode)
    }

    override suspend fun cacheSubtitle(file: StorageFile): String? {
        val videoData = (file as RemoteStorageFile).getRealFile()
        val result = RemoteRepository.getRelatedSubtitles(this, videoData.Id)

        val subtitleFileName =
            result
                .getOrNull()
                ?.subtitles
                ?.firstOrNull()
                ?.fileName
                ?: return null

        return RemoteRepository
            .downloadSubtitle(this, videoData.Id, subtitleFileName)
            .getOrNull()
            ?.byteStream()
            ?.let { SubtitleUtils.saveSubtitle(subtitleFileName, it) }
    }

    override fun supportSearch(): Boolean = true

    override suspend fun search(keyword: String): List<StorageFile> {
        if (keyword.isEmpty()) {
            return openDirectory(directory ?: getRootFile(), false)
        }
        return storageFilesSnapshot
            .filter { it.getEpisodeName().contains(keyword) || it.Path.contains(keyword) }
            .map { RemoteStorageFile(this, it) }
    }

    override suspend fun test(): Boolean = getRemoteRootFiles() != null

    private suspend fun getRemoteRootFiles(): List<RemoteVideoData>? {
        val result = fetchFiles(this)
        if (result.isFailure) {
            val exception = result.exceptionOrNull() ?: return null
            if (exception.cause is CancellationException) {
                // ignore
            } else {
                val message =
                    if (exception is NetworkException && exception.code == 401) {
                        "连接失败：密钥验证失败"
                    } else {
                        "连接失败：${exception.message}"
                    }
                ErrorReportHelper.postCatchedException(exception, "RemoteStorage", message)
            }
            return null
        }

        return result.getOrNull()?.let {
            storageFilesSnapshot = it

            if (groupByAnime) {
                RemoteFileHelper.convertGroupData(it)
            } else {
                RemoteFileHelper.convertTreeData(it)
            }
        }
    }

    private fun generateRootUri(): Uri {
        // 兼容旧模式数据
        val noScheme = Uri.parse(library.url).scheme.isNullOrEmpty()
        if (noScheme) {
            return Uri.parse("http://${library.url}:${library.port}")
        }

        return Uri.parse(library.url)
    }
}
