package com.xyoye.common_component.storage.impl

import android.net.Uri
import com.xunlei.downloadlib.parameter.TorrentFileInfo
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.helper.PlayTaskManager
import com.xyoye.common_component.storage.file.helper.TorrentBean
import com.xyoye.common_component.storage.file.impl.TorrentStorageFile
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.thunder.ThunderManager
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import java.io.InputStream

/**
 * Created by xyoye on 2023/4/3
 */

class TorrentStorage(
    library: MediaLibraryEntity
) : AbstractStorage(library) {
    init {
        try {
            PlayTaskManager.init()
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
                ToastCenter.showError("获取种子文件失败")
                return emptyList()
            }
            if (torrent.mSubFileInfo.isNullOrEmpty()) {
                ErrorReportHelper.postException(
                    "Torrent file has no sub files",
                    "TorrentStorage",
                    RuntimeException("Torrent path: ${torrent.torrentPath}"),
                )
                ToastCenter.showError("解析种子文件失败")
                return emptyList()
            }
            torrent.mSubFileInfo.map {
                TorrentStorageFile(this, it)
            }
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "TorrentStorage",
                "listFiles",
                "文件名: ${file.fileName()}",
            )
            ToastCenter.showError("列举种子文件失败")
            emptyList()
        }
    }

    override suspend fun getRootFile(): StorageFile =
        try {
            TorrentStorageFile(
                this,
                TorrentFileInfo().apply {
                    mFileIndex = -1
                    mSubPath = library.url
                },
            )
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "TorrentStorage",
                "getRootFile",
                "媒体库URL: ${library.url}",
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
                    ThunderManager.getInstance().getTaskInfo(torrentPath),
                )

            val fileInfo =
                torrent.mSubFileInfo?.find {
                    it.mFileIndex == history.torrentIndex
                } ?: run {
                    ErrorReportHelper.postException(
                        "File not found in torrent",
                        "TorrentStorage",
                        RuntimeException("Torrent index ${history.torrentIndex} not found in torrent: $torrentPath"),
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
                "种子路径: ${history.torrentPath}, 索引: ${history.torrentIndex}",
            )
            null
        }
    }

    override suspend fun createPlayUrl(file: StorageFile): String? {
        return try {
            val torrent =
                getTorrentFormFile(file)
                    ?: run {
                        ErrorReportHelper.postException(
                            "Failed to get torrent for play URL creation",
                            "TorrentStorage",
                            RuntimeException("getTorrentFormFile returned null for file: ${file.fileName()}"),
                        )
                        return null
                    }
            val fileIndex = (file as TorrentStorageFile).getRealFile().mFileIndex
            if (fileIndex == -1) {
                ErrorReportHelper.postException(
                    "Invalid file index for play URL creation",
                    "TorrentStorage",
                    RuntimeException("File index is -1 for file: ${file.fileName()}"),
                )
                return null
            }
            ThunderManager.getInstance().generatePlayUrl(torrent, fileIndex)
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "TorrentStorage",
                "createPlayUrl",
                "文件名: ${file.fileName()}",
            )
            null
        }
    }

    override suspend fun cacheDanmu(file: StorageFile): LocalDanmuBean? = null

    override suspend fun cacheSubtitle(file: StorageFile): String? = null

    private suspend fun torrentPath(url: String): String? =
        try {
            val isMagnetLink = Uri.parse(url).scheme == "magnet"
            if (isMagnetLink) {
                ThunderManager.getInstance().downloadTorrentFile(url)
            } else {
                url
            }
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "TorrentStorage",
                "torrentPath",
                "URL: $url",
            )
            null
        }

    private suspend fun getTorrentFormFile(file: StorageFile): TorrentBean? {
        return try {
            val directoryInfo = (file as TorrentStorageFile).getRealFile()
            val torrentPath = torrentPath(directoryInfo.mSubPath)
            if (torrentPath.isNullOrEmpty()) {
                ErrorReportHelper.postException(
                    "Failed to get torrent path",
                    "TorrentStorage",
                    RuntimeException("torrentPath returned null for path: ${directoryInfo.mSubPath}"),
                )
                return null
            }
            TorrentBean.formInfo(
                torrentPath,
                ThunderManager.getInstance().getTaskInfo(torrentPath),
            )
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
