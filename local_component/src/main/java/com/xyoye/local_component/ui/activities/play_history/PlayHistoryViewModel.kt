package com.xyoye.local_component.ui.activities.play_history

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.database.repository.PlayHistoryRepository
import com.xyoye.common_component.playback.PlaybackEntryRequests
import com.xyoye.common_component.playback.handoff.ExternalUriDescriptor
import com.xyoye.common_component.playback.handoff.PlayHistoryDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.media3.entity.Media3SourceType
import com.xyoye.local_component.utils.HistorySortOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PlayHistoryViewModel : BaseViewModel() {
    private val _historyLiveData = MutableLiveData<List<PlayHistoryEntity>>()
    val historyLiveData: LiveData<List<PlayHistoryEntity>> = _historyLiveData
    private val requests = PlaybackEntryRequests(viewModelScope, { loading -> if (loading) showLoading() else hideLoading() })
    val playEvents = requests.outcomes

    // 文件排序选项
    private var sortOption = HistorySortOption()

    var mediaType = MediaType.OTHER_STORAGE

    fun updatePlayHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val historyData =
                    if (mediaType == MediaType.OTHER_STORAGE) {
                        PlayHistoryRepository.getAll()
                    } else {
                        PlayHistoryRepository.getSingleMediaType(mediaType)
                    }
                _historyLiveData.postValue(historyData)
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayHistoryViewModel",
                    "updatePlayHistory",
                    "mediaType" to mediaType.name
                )
            }
        }
    }

    fun removeHistory(history: PlayHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                PlayHistoryRepository.delete(history.id)
                updatePlayHistory()
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayHistoryViewModel",
                    "removeHistory",
                    "historyId" to history.id,
                    "url" to history.url
                )
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (mediaType == MediaType.STREAM_LINK || mediaType == MediaType.MAGNET_LINK) {
                    PlayHistoryRepository.deleteTypeAll(listOf(mediaType))
                } else {
                    PlayHistoryRepository.deleteAll()
                }
                updatePlayHistory()
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayHistoryViewModel",
                    "clearHistory",
                    "mediaType" to mediaType.name
                )
            }
        }
    }

    /**
     * 修改文件排序
     */
    fun changeSortOption(option: HistorySortOption) {
        sortOption = option
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentFiles = _historyLiveData.value ?: return@launch
                mutableListOf<PlayHistoryEntity>()
                    .plus(currentFiles)
                    .sortedWith(sortOption.createComparator())
                    .apply { _historyLiveData.postValue(this) }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayHistoryViewModel",
                    "changeSortOption",
                    "sortOption" to option.javaClass.simpleName
                )
            }
        }
    }

    fun unbindDanmu(history: PlayHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newHistory = history.copy(danmuPath = null, episodeId = null)
                PlayHistoryRepository.insert(newHistory)
                updatePlayHistory()
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayHistoryViewModel",
                    "unbindDanmu",
                    "historyId" to history.id,
                    "url" to history.url
                )
            }
        }
    }

    fun unbindSubtitle(history: PlayHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newHistory = history.copy(subtitlePath = null)
                PlayHistoryRepository.insert(newHistory)
                updatePlayHistory()
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayHistoryViewModel",
                    "unbindSubtitle",
                    "historyId" to history.id,
                    "url" to history.url
                )
            }
        }
    }

    fun openHistory(history: PlayHistoryEntity) {
        val descriptor = PlayHistoryDescriptor(history.id, history.uniqueKey, history.storageId)
        requests.submit { PlaybackRequestInput(descriptor, null, PlaybackRequestOrigin.PLAY_HISTORY) }
    }

    fun openStreamLink(
        link: String,
        headers: Map<String, String>?
    ) {
        val descriptor = ExternalUriDescriptor(link, Media3SourceType.STREAM, headers = headers.orEmpty().toMap())
        requests.submit { PlaybackRequestInput(descriptor, null, PlaybackRequestOrigin.PLAY_HISTORY) }
    }
}
