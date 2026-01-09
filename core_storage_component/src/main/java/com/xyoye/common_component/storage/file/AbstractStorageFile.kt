package com.xyoye.common_component.storage.file

import com.xyoye.common_component.extension.isInvalid
import com.xyoye.common_component.extension.toCoverFile
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.Storage
import com.xyoye.data_component.entity.PlayHistoryEntity

/**
 * Created by xyoye on 2022/12/29
 */

abstract class AbstractStorageFile(
    abstractStorage: AbstractStorage
) : StorageFile {
    private val uniqueKey: String by lazy {
        val libraryId = storage.library.id
        val filePath = fileUrl()
        "$libraryId-$filePath".toMd5String()
    }

    override var storage: Storage = abstractStorage

    override var playHistory: PlayHistoryEntity? = null

    override fun fileCover(): String? {
        if (isDirectory()) {
            return null
        }
        val cachedCoverFile =
            uniqueKey().toCoverFile()
                ?: return null
        if (cachedCoverFile.isInvalid()) {
            return null
        }
        return cachedCoverFile.absolutePath
    }

    override fun storagePath(): String = filePath()

    override fun uniqueKey(): String = uniqueKey

    override fun isFile(): Boolean = isDirectory().not()

    override fun isRootFile(): Boolean = fileUrl() == storage.rootUri.toString()

    override fun childFileCount(): Int = 0

    override fun <T> getFile(): T? = getRealFile() as? T

    override fun close() {
        // do nothing
    }

    override fun canRead(): Boolean = true

    override fun isVideoFile(): Boolean =
        com.xyoye.common_component.utils
            .isVideoFile(fileName())

    override fun isStoragePathParent(childPath: String): Boolean = childPath.startsWith(storagePath())

    override fun videoDuration(): Long = 0

    abstract fun getRealFile(): Any
}
