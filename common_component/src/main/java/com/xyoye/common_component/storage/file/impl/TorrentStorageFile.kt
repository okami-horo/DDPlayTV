package com.xyoye.common_component.storage.file.impl

import android.net.Uri
import com.xunlei.downloadlib.parameter.TorrentFileInfo
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.storage.file.AbstractStorageFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.TorrentStorage
import com.xyoye.common_component.utils.getFileNameNoExtension

/**
 * Created by xyoye on 2023/4/3
 */

class TorrentStorageFile(
    storage: TorrentStorage,
    private val fileInfo: TorrentFileInfo
) : AbstractStorageFile(storage) {
    override fun getRealFile(): TorrentFileInfo = fileInfo

    override fun filePath(): String = fileInfo.mSubPath

    override fun fileUrl(): String = Uri.parse(fileInfo.mSubPath).toString()

    override fun isDirectory(): Boolean = fileInfo.mFileIndex == -1

    override fun fileName(): String = fileInfo.mFileName

    override fun fileLength(): Long = fileInfo.mFileSize

    override fun clone(): StorageFile =
        TorrentStorageFile(storage as TorrentStorage, fileInfo).also {
            it.playHistory = playHistory
        }

    override fun uniqueKey(): String {
        val hash = getFileNameNoExtension(fileInfo.mSubPath)
        return (hash + "_" + fileInfo.mFileIndex).toMd5String()
    }
}
