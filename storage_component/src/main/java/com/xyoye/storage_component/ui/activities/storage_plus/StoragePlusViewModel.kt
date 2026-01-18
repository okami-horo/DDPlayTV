package com.xyoye.storage_component.ui.activities.storage_plus

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferencesStore
import com.xyoye.common_component.bilibili.auth.BilibiliCookieJarStore
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StoragePlusViewModel : BaseViewModel() {
    private val _testLiveData = MutableLiveData<Boolean>()
    var testLiveData: LiveData<Boolean> = _testLiveData

    private val _storageSavedLiveData = MutableLiveData<MediaLibraryEntity>()
    var storageSavedLiveData: LiveData<MediaLibraryEntity> = _storageSavedLiveData

    private var editingLibraryId: Int? = null

    fun addStorage(
        oldLibrary: MediaLibraryEntity?,
        newLibrary: MediaLibraryEntity,
        showToast: Boolean = true,
    ): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            if (oldLibrary != null) {
                editingLibraryId = oldLibrary.id
            }
            val oldLibraryId = oldLibrary?.id ?: editingLibraryId
            val upsertLibrary = newLibrary.copy()
            if (upsertLibrary.mediaType == MediaType.OPEN_115_STORAGE) {
                upsertLibrary.url = upsertLibrary.url.trim().removeSuffix("/")
                val isValid = Regex("^115open://uid/\\d+$").matches(upsertLibrary.url)
                if (!isValid) {
                    if (showToast) {
                        ToastCenter.showWarning("请先测试连接/保存")
                    }
                    return@launch
                }
            }

            if (upsertLibrary.mediaType == MediaType.BAIDU_PAN_STORAGE) {
                upsertLibrary.url = upsertLibrary.url.trim().removeSuffix("/")
                val isValid = Regex("^baidupan://uk/\\d+$").matches(upsertLibrary.url)
                if (!isValid) {
                    if (showToast) {
                        ToastCenter.showWarning("保存失败，请先扫码授权")
                    }
                    return@launch
                }
            }

            if (upsertLibrary.mediaType == MediaType.BILIBILI_STORAGE) {
                if (upsertLibrary.url.isBlank()) {
                    upsertLibrary.url = Api.BILI_BILI_API
                }
                upsertLibrary.url = upsertLibrary.url.trim().removeSuffix("/")
                val storageKey = BilibiliPlaybackPreferencesStore.storageKey(upsertLibrary)
                val isLoggedIn = BilibiliCookieJarStore(storageKey).isLoginCookiePresent()
                if (!isLoggedIn) {
                    if (showToast) {
                        ToastCenter.showWarning("保存失败，请先扫码登录")
                    }
                    return@launch
                }
            }

            val duplicateLibrary =
                DatabaseManager.instance
                    .getMediaLibraryDao()
                    .getByUrl(upsertLibrary.url, upsertLibrary.mediaType)
            if (duplicateLibrary != null && duplicateLibrary.id != oldLibraryId) {
                if (showToast) {
                    ToastCenter.showError("保存失败，媒体库地址已存在")
                }
                return@launch
            }

            upsertLibrary.id = oldLibraryId ?: upsertLibrary.id
            DatabaseManager.instance.getMediaLibraryDao().insert(upsertLibrary)
            val savedLibrary =
                DatabaseManager.instance
                    .getMediaLibraryDao()
                    .getByUrl(upsertLibrary.url, upsertLibrary.mediaType)
                    ?: upsertLibrary
            if (savedLibrary.id != 0) {
                editingLibraryId = savedLibrary.id
            }
            withContext(Dispatchers.Main) {
                _storageSavedLiveData.value = savedLibrary
            }
        }
    }

    fun testStorage(library: MediaLibraryEntity) {
        val storage = StorageFactory.createStorage(library)
        viewModelScope.launch(Dispatchers.IO) {
            showLoading()
            val status = storage?.test() ?: false
            hideLoading()
            _testLiveData.postValue(status)
        }
    }
}
