package com.xyoye.local_component.ui.fragment.media

import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferencesStore
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.extension.aesEncode
import com.xyoye.common_component.extension.authorizationValue
import com.xyoye.common_component.extension.toastError
import com.xyoye.common_component.network.repository.ScreencastRepository
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanAuthStore
import com.xyoye.common_component.storage.open115.auth.Open115AuthStore
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.getFileName
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Created by xyoye on 2020/7/27.
 */

class MediaViewModel : BaseViewModel() {
    val mediaLibWithStatusLiveData =
        DatabaseManager.instance
            .getMediaLibraryDao()
            .getAll()
            .map { libraries ->
                libraries
                    .onEach {
                        it.running = false
                    }.filter { it.mediaType != MediaType.SCREEN_CAST }
                    .toMutableList()
            }

    fun initLocalStorage() {
        viewModelScope.launch(context = Dispatchers.IO) {
            try {
                // 播放历史首条记录
                DatabaseManager.instance
                    .getPlayHistoryDao()
                    .gitLastPlay(
                        MediaType.LOCAL_STORAGE,
                        MediaType.OTHER_STORAGE,
                        MediaType.FTP_SERVER,
                        MediaType.SMB_SERVER,
                        MediaType.REMOTE_STORAGE,
                        MediaType.WEBDAV_SERVER,
                    )?.apply {
                        MediaLibraryEntity.HISTORY.url = url
                    }

                // 磁链播放首条记录
                DatabaseManager.instance.getPlayHistoryDao().gitLastPlay(MediaType.MAGNET_LINK)?.apply {
                    MediaLibraryEntity.TORRENT.describe = getFileName(torrentPath)
                }

                // 串流播放首条记录
                DatabaseManager.instance.getPlayHistoryDao().gitLastPlay(MediaType.STREAM_LINK)?.apply {
                    MediaLibraryEntity.STREAM.describe = url
                }

                DatabaseManager.instance
                    .getMediaLibraryDao()
                    .insert(
                        MediaLibraryEntity.LOCAL,
                        MediaLibraryEntity.STREAM,
                        MediaLibraryEntity.TORRENT,
                        MediaLibraryEntity.HISTORY,
                    )
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "MediaViewModel",
                    "initLocalStorage",
                    "Database operation failed",
                )
            }
        }
    }

    fun deleteStorage(data: MediaLibraryEntity) {
        viewModelScope.launch(context = Dispatchers.IO) {
            try {
                if (data.mediaType == MediaType.BILIBILI_STORAGE) {
                    BilibiliPlaybackPreferencesStore.clear(data)
                }
                if (data.mediaType == MediaType.BAIDU_PAN_STORAGE) {
                    BaiduPanAuthStore.clear(BaiduPanAuthStore.storageKey(data))
                }
                if (data.mediaType == MediaType.OPEN_115_STORAGE) {
                    Open115AuthStore.clear(Open115AuthStore.storageKey(data))
                }
                DatabaseManager.instance
                    .getMediaLibraryDao()
                    .delete(data.url, data.mediaType)
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "MediaViewModel",
                    "deleteStorage",
                    "Failed to delete storage: ${data.url}",
                )
            }
        }
    }

    fun checkScreenDeviceRunning(receiver: MediaLibraryEntity) {
        viewModelScope.launch {
            try {
                showLoading()
                val result =
                    ScreencastRepository.init(
                        "http://${receiver.screencastAddress}:${receiver.port}",
                        receiver.password?.aesEncode()?.authorizationValue(),
                    )
                hideLoading()

                if (result.isFailure) {
                    val exception = result.exceptionOrNull()
                    ErrorReportHelper.postCatchedExceptionWithContext(
                        exception ?: RuntimeException("Unknown screencast connection error"),
                        "MediaViewModel",
                        "checkScreenDeviceRunning",
                        "Screencast address: ${receiver.screencastAddress}:${receiver.port}",
                    )
                    exception?.message?.toastError()
                    return@launch
                }

                ToastCenter.showSuccess("投屏设备连接正常，请前往其它媒体库选择文件投屏")
            } catch (e: Exception) {
                hideLoading()
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "MediaViewModel",
                    "checkScreenDeviceRunning",
                    "Unexpected error during screencast check",
                )
                e.message?.toastError()
            }
        }
    }
}
