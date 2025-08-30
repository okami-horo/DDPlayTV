package com.xyoye.common_component.storage.file.helper

import com.xunlei.downloadlib.parameter.TorrentInfo
import com.xyoye.common_component.utils.ErrorReportHelper

/**
 * Created by xyoye on 2023/4/6
 */

class TorrentBean(
    val torrentPath: String
) : TorrentInfo() {
    companion object {
        fun formInfo(torrentPath: String, info: TorrentInfo): TorrentBean {
            return try {
                TorrentBean(torrentPath).apply {
                    mFileCount = info.mFileCount
                    mInfoHash = info.mInfoHash
                    mIsMultiFiles = info.mIsMultiFiles
                    mMultiFileBaseFolder = info.mMultiFileBaseFolder
                    mSubFileInfo = info.mSubFileInfo?.onEach { it.mSubPath = torrentPath }
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "TorrentBean",
                    "formInfo",
                    "种子路径: $torrentPath"
                )
                throw e
            }
        }
    }
}