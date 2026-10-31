package com.xyoye.storage_component.ui.activities.storage_plus

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.database.repository.MediaLibraryRepository
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.storage.cloud115.auth.Cloud115AuthStore
import com.xyoye.common_component.storage.credential.MediaLibraryCredentialStore
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
        showToast: Boolean = true
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

            if (upsertLibrary.mediaType == MediaType.CLOUD_115_STORAGE) {
                upsertLibrary.url = upsertLibrary.url.trim().removeSuffix("/")
                val userId = upsertLibrary.url.substringAfter("115cloud://uid/", missingDelimiterValue = "").trim()
                val isValid = Regex("^115cloud://uid/\\d+$").matches(upsertLibrary.url)
                if (!isValid || userId.isBlank()) {
                    if (showToast) {
                        ToastCenter.showWarning("保存失败，请先完成授权")
                    }
                    return@launch
                }
                upsertLibrary.url = "115cloud://uid/$userId"

                val isEditMode = (oldLibraryId ?: upsertLibrary.id) > 0
                if (!isEditMode) {
                    val storageKey = Cloud115AuthStore.storageKey(upsertLibrary)
                    val isAuthorized = Cloud115AuthStore.read(storageKey).isAuthorized()
                    if (!isAuthorized) {
                        if (showToast) {
                            ToastCenter.showWarning("保存失败，请先完成授权")
                        }
                        return@launch
                    }
                }
            }

            val duplicateLibrary =
                MediaLibraryRepository.getByUrl(upsertLibrary.url, upsertLibrary.mediaType)
            if (duplicateLibrary != null && duplicateLibrary.id != oldLibraryId) {
                if (showToast) {
                    ToastCenter.showError("保存失败，媒体库地址已存在")
                }
                return@launch
            }

            val pendingPassword = upsertLibrary.password?.takeIf { it.isNotBlank() }
            val pendingRemoteSecret = upsertLibrary.remoteSecret?.takeIf { it.isNotBlank() }
            upsertLibrary.password = null
            upsertLibrary.remoteSecret = null

            upsertLibrary.id = oldLibraryId ?: upsertLibrary.id
            MediaLibraryRepository.insert(upsertLibrary, invalidateRequests = true)
            val savedLibrary =
                MediaLibraryRepository.getByUrl(upsertLibrary.url, upsertLibrary.mediaType)
                    ?: upsertLibrary
            if (savedLibrary.id != 0) {
                editingLibraryId = savedLibrary.id
            }

            val savedLibraryId = savedLibrary.id
            if (savedLibraryId > 0) {
                val passwordSaved = MediaLibraryCredentialStore.writePassword(savedLibraryId, pendingPassword)
                val secretSaved = MediaLibraryCredentialStore.writeRemoteSecret(savedLibraryId, pendingRemoteSecret)
                MediaLibraryRepository.invalidateCatalogRequests(savedLibraryId)
                if (showToast && (pendingPassword != null && !passwordSaved || pendingRemoteSecret != null && !secretSaved)) {
                    withContext(Dispatchers.Main) {
                        ToastCenter.showWarning("安全存储不可用：凭据不会被保存")
                    }
                }
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
