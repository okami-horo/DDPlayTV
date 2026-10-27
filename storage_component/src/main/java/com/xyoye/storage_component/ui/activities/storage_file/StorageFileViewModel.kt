package com.xyoye.storage_component.ui.activities.storage_file

import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.extension.toMedia3SourceType
import com.xyoye.common_component.playback.PlaybackEntryRequests
import com.xyoye.common_component.playback.handoff.PlaybackLaunchOptions
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.TorrentDescriptor
import com.xyoye.common_component.source.handoff.PlaybackReferenceFactory
import com.xyoye.common_component.storage.file.StorageFile

class StorageFileViewModel : BaseViewModel() {
    private val requests = PlaybackEntryRequests(viewModelScope, { loading -> if (loading) showLoading() else hideLoading() })
    val playEvents = requests.outcomes

    fun playItem(file: StorageFile) {
        val descriptor = PlaybackReferenceFactory.fromFile(file)
        val options =
            PlaybackLaunchOptions(
                mediaId = (descriptor as? TorrentDescriptor)?.downloadId ?: file.uniqueKey(),
                sourceType =
                    file.storage.library.mediaType
                        .toMedia3SourceType()
            )
        requests.submit { PlaybackRequestInput(descriptor, options, PlaybackRequestOrigin.STORAGE_FILE) }
    }

//    fun locateLastPlay(storage: Storage) {
//        viewModelScope.launch(Dispatchers.IO) {
//            try {
//                val history = DatabaseProvider.instance.getPlayHistoryDao()
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
}
