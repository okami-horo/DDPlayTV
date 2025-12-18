package com.xyoye.common_component.storage.file.impl

import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.storage.file.AbstractStorageFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.RemoteStorage
import com.xyoye.data_component.data.remote.RemoteVideoData

/**
 * Created by xyoye on 2023/4/1.
 */

class RemoteStorageFile(
    storage: RemoteStorage,
    private val videoData: RemoteVideoData
) : AbstractStorageFile(storage) {
    override fun getRealFile(): RemoteVideoData = videoData

    override fun filePath(): String = videoData.absolutePath

    override fun fileUrl(): String {
        if (videoData.absolutePath == "/" && videoData.isFolder) {
            return storage.rootUri.toString()
        }
        return storage.rootUri
            .buildUpon()
            .path("/api/v1/stream/id/${videoData.Id}")
            .toString()
    }

    override fun isDirectory(): Boolean = videoData.isFolder

    override fun fileName(): String = videoData.getEpisodeName()

    override fun fileLength(): Long {
        if (isDirectory()) {
            return 0
        }
        return videoData.Size
    }

    override fun clone(): StorageFile =
        RemoteStorageFile(
            storage as RemoteStorage,
            videoData,
        ).also { it.playHistory = playHistory }

    override fun uniqueKey(): String = videoData.Hash.ifEmpty { videoData.absolutePath }.toMd5String()

    override fun childFileCount(): Int = videoData.childData.size

    override fun isVideoFile(): Boolean = isFile()

    override fun videoDuration(): Long = videoData.Duration
}
