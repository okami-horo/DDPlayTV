package com.xyoye.common_component.storage.impl

import android.net.Uri
import com.xyoye.common_component.log.privacy.SensitiveDataSanitizer
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.helper.PlayTaskManager
import com.xyoye.common_component.storage.file.helper.TorrentBean
import com.xyoye.common_component.storage.file.impl.TorrentStorageFile
import com.xyoye.common_component.storage.transport.PlaybackTransportLease
import com.xyoye.common_component.storage.transport.PlaybackTransportProvider
import com.xyoye.common_component.storage.transport.ThunderPlaybackTransport
import com.xyoye.common_component.storage.transport.TransportAcquisitionException
import com.xyoye.common_component.storage.transport.TransportAcquisitionFailure
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.thunder.ThunderManager
import com.xyoye.common_component.utils.thunder.model.ThunderTorrentFileInfo
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream

/**
 * Created by xyoye on 2023/4/3
 */

class TorrentStorage internal constructor(
    library: MediaLibraryEntity,
    private val manager: ThunderManager,
    private val resolveTorrent: (suspend (StorageFile) -> TorrentBean?)? = null,
    initialize: () -> Unit = PlayTaskManager::init
) : AbstractStorage(library), PlaybackTransportProvider {
    constructor(library: MediaLibraryEntity) : this(library, ThunderManager.getInstance())

    init {
        try {
            initialize()
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "TorrentStorage",
                "init",
                "初始化PlayTaskManager失败",
            )
        }
    }

    override suspend fun listFiles(file: StorageFile): List<StorageFile> {
        return try {
            val torrent = getTorrentFormFile(file)
            if (torrent == null) {
                ErrorReportHelper.postException(
                    "Failed to get torrent from file",
                    "TorrentStorage",
                    RuntimeException("getTorrentFormFile returned null for file: ${file.fileName()}"),
                )
                return emptyList()
            }
            if (torrent.subFileInfo.isEmpty()) {
                ErrorReportHelper.postException(
                    "Torrent file has no sub files",
                    "TorrentStorage",
                    RuntimeException("Torrent path: ${SensitiveDataSanitizer.sanitizePath(torrent.torrentPath)}"),
                )
                return emptyList()
            }
            torrent.subFileInfo.map {
                TorrentStorageFile(this, it)
            }
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "TorrentStorage",
                "listFiles",
                "文件名: ${file.fileName()}",
            )
            emptyList()
        }
    }

    override suspend fun getRootFile(): StorageFile =
        try {
            TorrentStorageFile(
                this,
                ThunderTorrentFileInfo.createRoot(library.url),
            )
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "TorrentStorage",
                "getRootFile",
                "媒体库URL: ${SensitiveDataSanitizer.sanitizeUrl(library.url)}",
            )
            throw e
        }

    override suspend fun openFile(file: StorageFile): InputStream? = null

    override suspend fun pathFile(
        path: String,
        isDirectory: Boolean
    ): StorageFile? = null

    override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? {
        return try {
            val torrentPath =
                history.torrentPath
                    ?: return null
            val torrent =
                TorrentBean.formInfo(
                    torrentPath,
                    manager.getTaskInfo(torrentPath),
                )

            val fileInfo =
                torrent.subFileInfo.find {
                    it.fileIndex == history.torrentIndex
                } ?: run {
                    val sanitizedTorrentPath = SensitiveDataSanitizer.sanitizePath(torrentPath)
                    ErrorReportHelper.postException(
                        "File not found in torrent",
                        "TorrentStorage",
                        RuntimeException(
                            "Torrent index ${history.torrentIndex} not found in torrent: $sanitizedTorrentPath",
                        ),
                    )
                    return null
                }

            TorrentStorageFile(this, fileInfo).also {
                it.playHistory = history
            }
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "TorrentStorage",
                "historyFile",
                "种子路径: ${SensitiveDataSanitizer.sanitizePath(history.torrentPath)}, 索引: ${history.torrentIndex}",
            )
            null
        }
    }

    override suspend fun acquirePlaybackTransport(
        file: StorageFile,
        profile: PlaybackProfile
    ): PlaybackTransportLease {
        val torrentFile = file as? TorrentStorageFile
        if (torrentFile == null || file.storage !== this || !file.isFile()) {
            throw TransportAcquisitionException(TransportAcquisitionFailure.UpstreamUnavailable)
        }
        val torrent = getTorrentFormFile(file)
            ?: throw TransportAcquisitionException(TransportAcquisitionFailure.UpstreamUnavailable)
        val task = manager.acquirePlaybackTask(torrent, torrentFile.getFileInfo().fileIndex)
        var transferred = false
        try {
            currentCoroutineContext().ensureActive()
            val lease = ThunderPlaybackTransport(task, manager)
            transferred = true
            return lease
        } finally {
            if (!transferred) manager.releasePlaybackTask(task.taskId, task.leaseId)
        }
    }

    override suspend fun cacheDanmu(file: StorageFile): LocalDanmuBean? = null

    override suspend fun cacheSubtitle(file: StorageFile): String? = null

    private suspend fun torrentPath(url: String): String? =
        try {
            val isMagnetLink = Uri.parse(url).scheme == "magnet"
            if (isMagnetLink) {
                manager.downloadTorrentFile(url)
            } else {
                url
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "TorrentStorage",
                "torrentPath",
                "URL: ${SensitiveDataSanitizer.sanitizeUrl(url)}",
            )
            null
        }

    private suspend fun getTorrentFormFile(file: StorageFile): TorrentBean? {
        resolveTorrent?.let { return it(file) }
        return try {
            val directoryInfo = (file as TorrentStorageFile).getFileInfo()
            val torrentPath = torrentPath(directoryInfo.subPath)
            if (torrentPath.isNullOrEmpty()) {
                ErrorReportHelper.postException(
                    "Failed to get torrent path",
                    "TorrentStorage",
                    RuntimeException(
                        "torrentPath returned null for path: ${SensitiveDataSanitizer.sanitizeUrl(directoryInfo.subPath)}",
                    ),
                )
                return null
            }
            TorrentBean.formInfo(
                torrentPath,
                manager.getTaskInfo(torrentPath),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "TorrentStorage",
                "getTorrentFormFile",
                "文件名: ${file.fileName()}",
            )
            null
        }
    }
}
