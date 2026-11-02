package com.xyoye.local_component.ui.fragment.media

import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanAuthStore
import com.xyoye.common_component.storage.cloud115.auth.Cloud115AuthStore
import com.xyoye.common_component.storage.credential.MediaLibraryCredentialStore
import com.xyoye.common_component.storage.open115.auth.Open115AuthStore
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Created by xyoye on 2020/7/27.
 */

class MediaViewModel : BaseViewModel() {
    val mediaLibWithStatusLiveData =
        MediaLibraryRepository
            .getAll()
            .map { libraries ->
                libraries
                    .onEach {
                        it.running = false
                    }.filter { it.mediaType != MediaType.SCREEN_CAST && it.mediaType != MediaType.OTHER_STORAGE }
                    .toMutableList()
            }

    fun initLocalStorage() {
        viewModelScope.launch(context = Dispatchers.IO) {
            try {
                MediaLibraryRepository.insert(MediaLibraryEntity.LOCAL)
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
                if (data.mediaType == MediaType.BAIDU_PAN_STORAGE) {
                    BaiduPanAuthStore.clear(BaiduPanAuthStore.storageKey(data))
                }
                if (data.mediaType == MediaType.OPEN_115_STORAGE) {
                    Open115AuthStore.clear(Open115AuthStore.storageKey(data))
                }
                if (data.mediaType == MediaType.CLOUD_115_STORAGE) {
                    Cloud115AuthStore.clear(Cloud115AuthStore.storageKey(data))
                }
                if (data.id > 0) {
                    MediaLibraryCredentialStore.clear(data.id)
                }
                MediaLibraryRepository.delete(data.url, data.mediaType)
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "MediaViewModel",
                    "deleteStorage",
                    "mediaType" to data.mediaType.name,
                    "url" to data.url,
                )
            }
        }
    }
}
