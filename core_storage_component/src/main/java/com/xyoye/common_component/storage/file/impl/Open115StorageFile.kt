package com.xyoye.common_component.storage.file.impl

import com.xyoye.common_component.storage.file.AbstractStorageFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.Open115Storage
import com.xyoye.common_component.utils.isVideoFile as isVideoFileByName
import com.xyoye.data_component.data.open115.Open115FileItem

class Open115StorageFile(
    private val fileItem: Open115FileItem,
    private val parentPath: String,
    storage: Open115Storage,
    private val root: Boolean = false
) : AbstractStorageFile(storage) {
    override fun getRealFile(): Any = fileItem

    override fun filePath(): String {
        if (root) return "/"

        val fid = fileItem.fid.orEmpty()
        val base = parentPath.removeSuffix("/")
        val path =
            if (base.isBlank()) {
                "/$fid"
            } else {
                "$base/$fid"
            }
        return if (isDirectory()) "$path/" else path
    }

    override fun fileUrl(): String = "115open://file/${fileItem.fid.orEmpty()}"

    override fun isDirectory(): Boolean = fileItem.fc == "0"

    override fun fileName(): String =
        fileItem.fn
            ?.takeIf { it.isNotBlank() }
            ?: if (root) {
                "根目录"
            } else {
                fileItem.fid.orEmpty()
            }

    override fun fileLength(): Long = fileItem.fs ?: 0L

    override fun isRootFile(): Boolean = root

    override fun isVideoFile(): Boolean {
        if (isDirectory()) return false
        if (fileItem.isv == 1L) return true
        return isVideoFileByName(fileName())
    }

    override fun clone(): StorageFile =
        Open115StorageFile(
            fileItem = fileItem,
            parentPath = parentPath,
            storage = storage as Open115Storage,
            root = root
        ).also {
            it.playHistory = playHistory
        }

    companion object {
        fun root(storage: Open115Storage): Open115StorageFile =
            Open115StorageFile(
                fileItem =
                    Open115FileItem(
                        fid = Open115Storage.ROOT_CID,
                        pid = null,
                        fc = "0",
                        fn = "根目录",
                        pc = null,
                        sha1 = null,
                        fs = 0L,
                        upt = null,
                        uet = null,
                        uppt = null,
                        isv = null,
                        ico = null,
                        thumb = null
                    ),
                parentPath = "/",
                storage = storage,
                root = true
            )
    }
}
