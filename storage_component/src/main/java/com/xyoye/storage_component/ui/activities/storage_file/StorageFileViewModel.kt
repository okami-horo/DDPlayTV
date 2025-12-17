package com.xyoye.storage_component.ui.activities.storage_file

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.extension.toMedia3SourceType
import com.xyoye.common_component.source.VideoSourceManager
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.source.media3.Media3LaunchParams
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.thunder.ThunderManager
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.download.validator.DownloadValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StorageFileViewModel : BaseViewModel() {
    val playLiveData = MutableLiveData<Any>()
    val castLiveData = MutableLiveData<MediaLibraryEntity>()
    // val locateLastPlayLiveData = MutableLiveData<PlayHistoryEntity>()

    val selectDeviceLiveData = MutableLiveData<Pair<StorageFile, List<MediaLibraryEntity>>>()
    private val downloadValidator = DownloadValidator()

    fun playItem(file: StorageFile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (setupVideoSource(file) && ensureDownloadAllowed(file)) {
                    playLiveData.postValue(Any())
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "StorageFileViewModel",
                    "playItem",
                    "播放文件失败: ${file.fileName()}",
                )
                ToastCenter.showError("播放失败: ${e.message}")
            }
        }
    }

    fun castItem(file: StorageFile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 获取所有可用的投屏设备
                val devices =
                    DatabaseManager.instance
                        .getMediaLibraryDao()
                        .getByMediaTypeSuspend(MediaType.SCREEN_CAST)

                if (devices.isEmpty()) {
                    ToastCenter.showError("无可用投屏设备")
                    return@launch
                }
                if (devices.size == 1) {
                    castItem(file, devices.first())
                    return@launch
                }

                selectDeviceLiveData.postValue(file to devices)
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "StorageFileViewModel",
                    "castItem",
                    "投屏文件失败: ${file.fileName()}",
                )
                ToastCenter.showError("投屏失败: ${e.message}")
            }
        }
    }

    fun castItem(
        file: StorageFile,
        device: MediaLibraryEntity
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (setupVideoSource(file) && ensureDownloadAllowed(file)) {
                    castLiveData.postValue(device)
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "StorageFileViewModel",
                    "castItem",
                    "投屏到设备失败: ${device.displayName}, 文件: ${file.fileName()}",
                )
                ToastCenter.showError("投屏失败: ${e.message}")
            }
        }
    }

//    fun locateLastPlay(storage: Storage) {
//        viewModelScope.launch(Dispatchers.IO) {
//            try {
//                val history = DatabaseManager.instance.getPlayHistoryDao()
//                    .gitStorageLastPlay(storage.library.id)
//                if (history == null) {
//                    ToastCenter.showError("当前媒体库暂无播放记录")
//                    return@launch
//                }
//                history.isLastPlay = true
//                locateLastPlayLiveData.postValue(history)
//            } catch (e: Exception) {
//                ErrorReportHelper.postCatchedExceptionWithContext(
//                    e,
//                    "StorageFileViewModel",
//                    "locateLastPlay",
//                    "定位上次观看失败: ${storage.library.displayName}"
//                )
//                ToastCenter.showError("定位失败: ${e.message}")
//            }
//        }
//    }

    private suspend fun setupVideoSource(file: StorageFile): Boolean {
        showLoading()
        val mediaSource = StorageVideoSourceFactory.create(file)
        hideLoading()

        if (mediaSource == null) {
            ToastCenter.showError("播放失败，找不到播放资源")
            return false
        }
        val mediaType = file.storage.library.mediaType
        VideoSourceManager.getInstance().attachMedia3LaunchParams(
            Media3LaunchParams(
                mediaId = media3DownloadId(file),
                sourceType = mediaType.toMedia3SourceType(),
            ),
        )
        VideoSourceManager.getInstance().setSource(mediaSource)
        return true
    }

    private suspend fun ensureDownloadAllowed(file: StorageFile): Boolean {
        if (!requiresOfflineValidation(file)) {
            return true
        }
        val downloadId = media3DownloadId(file)
        val lastVerified = file.playHistory?.playTime?.time
        val outcome =
            downloadValidator.validate(
                downloadId = downloadId,
                mediaId = downloadId,
                lastVerifiedAt = lastVerified,
            )
        return when (outcome) {
            is DownloadValidator.ValidationOutcome.AllowPlayback -> {
                if (outcome.audioOnly) {
                    ToastCenter.showWarning(outcome.message ?: "当前资源仅支持音频播放")
                } else if (!outcome.message.isNullOrEmpty()) {
                    ToastCenter.showOriginalToast(outcome.message)
                }
                true
            }

            is DownloadValidator.ValidationOutcome.Blocked -> {
                ToastCenter.showError(outcome.reason)
                false
            }
        }
    }

    private fun requiresOfflineValidation(file: StorageFile): Boolean = file.storage.library.mediaType == MediaType.MAGNET_LINK

    private fun media3DownloadId(file: StorageFile): String {
        if (file.storage.library.mediaType == MediaType.MAGNET_LINK) {
            val source = file.playHistory?.torrentPath ?: file.storage.library.url
            val index = file.playHistory?.torrentIndex ?: -1
            return ThunderManager.media3DownloadId(source, index)
        }
        return file.uniqueKey()
    }
}
