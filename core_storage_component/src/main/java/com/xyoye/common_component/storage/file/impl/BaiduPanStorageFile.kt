package com.xyoye.common_component.storage.file.impl

import com.xyoye.common_component.storage.file.AbstractStorageFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.BaiduPanStorage
import com.xyoye.common_component.utils.isVideoFile as isVideoFileByName
import com.xyoye.data_component.data.baidupan.xpan.BaiduPanXpanFileItem

class BaiduPanStorageFile(
    private val fileItem: BaiduPanXpanFileItem,
    storage: BaiduPanStorage
) : AbstractStorageFile(storage) {
    override fun getRealFile(): Any = fileItem

    override fun filePath(): String = fileItem.path

    override fun fileUrl(): String = fileItem.path

    override fun isDirectory(): Boolean = fileItem.isdir == 1

    override fun fileName(): String =
        fileItem.serverFilename
            .ifBlank { fileItem.path.substringAfterLast('/', missingDelimiterValue = "/") }

    override fun fileLength(): Long = fileItem.size ?: 0L

    override fun isRootFile(): Boolean = fileItem.path == "/"

    override fun isVideoFile(): Boolean {
        if (isDirectory()) {
            return false
        }
        if (fileItem.category == 1) {
            return true
        }
        return isVideoFileByName(fileName())
    }

    override fun clone(): StorageFile =
        BaiduPanStorageFile(
            fileItem = fileItem,
            storage = storage as BaiduPanStorage,
        ).also {
            it.playHistory = playHistory
        }

    companion object {
        fun root(storage: BaiduPanStorage): BaiduPanStorageFile =
            BaiduPanStorageFile(
                fileItem =
                    BaiduPanXpanFileItem(
                        fsId = 0L,
                        path = "/",
                        serverFilename = "根目录",
                        isdir = 1,
                        size = null,
                        serverCtime = null,
                        serverMtime = null,
                        category = null,
                    ),
                storage = storage,
            )
    }
}

