package com.xyoye.common_component.storage.file.impl

import android.net.Uri
import com.xyoye.common_component.storage.file.AbstractStorageFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.helper.ScreencastConstants
import com.xyoye.common_component.storage.impl.ScreencastStorage
import com.xyoye.data_component.data.screeencast.ScreencastData
import com.xyoye.data_component.data.screeencast.ScreencastVideoData

/**
 * Created by xyoye on 2023/4/12
 */

class ScreencastStorageFile(
    storage: ScreencastStorage,
    private val screencastData: ScreencastData,
    private val videoData: ScreencastVideoData
) : AbstractStorageFile(storage) {
    override fun getRealFile(): ScreencastVideoData = videoData

    override fun filePath(): String = ScreencastConstants.ProviderApi.VIDEO.buildUrl(screencastData, videoData)

    override fun fileUrl(): String = Uri.parse(filePath()).toString()

    override fun isDirectory(): Boolean = false

    override fun fileName(): String = videoData.title

    override fun fileLength(): Long = 0

    override fun clone(): StorageFile =
        ScreencastStorageFile(
            storage as ScreencastStorage,
            screencastData,
            videoData,
        ).also { it.playHistory = playHistory }

    override fun isVideoFile(): Boolean = true

    override fun uniqueKey(): String = videoData.uniqueKey

    fun getCallbackUrl(): String = ScreencastConstants.ProviderApi.CALLBACK.buildUrl(screencastData, videoData)
}
