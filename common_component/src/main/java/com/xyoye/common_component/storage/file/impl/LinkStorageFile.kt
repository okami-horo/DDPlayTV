package com.xyoye.common_component.storage.file.impl

import android.net.Uri
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.storage.file.AbstractStorageFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.LinkStorage
import com.xyoye.common_component.utils.getFileName

/**
 * Created by xyoye on 2023/4/12
 */

class LinkStorageFile(
    storage: LinkStorage,
    private val url: String
) : AbstractStorageFile(storage) {
    override fun getRealFile(): Any = url

    override fun filePath(): String = url

    override fun fileUrl(): String = Uri.parse(url).toString()

    override fun isDirectory(): Boolean = false

    override fun fileName(): String = Uri.parse(url).lastPathSegment ?: getFileName(url)

    override fun fileLength(): Long = 0

    override fun clone(): StorageFile =
        LinkStorageFile(storage as LinkStorage, url).also {
            it.playHistory = playHistory
        }

    override fun isVideoFile(): Boolean = true

    override fun uniqueKey(): String = url.toMd5String()
}
