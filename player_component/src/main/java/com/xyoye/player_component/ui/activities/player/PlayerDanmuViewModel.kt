package com.xyoye.player_component.ui.activities.player

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.bilibili.BilibiliKeys
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferencesStore
import com.xyoye.common_component.bilibili.danmaku.BilibiliDanmakuDownloader
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.utils.danmu.DanmuFinder
import com.xyoye.common_component.utils.danmu.source.DanmuSourceFactory
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Created by xyoye on 2022/1/2.
 */

class PlayerDanmuViewModel : BaseViewModel() {
    val loadDanmuLiveData = MutableLiveData<Pair<String, LocalDanmuBean>>()
    val danmuSearchLiveData = MutableLiveData<List<DanmuEpisodeData>>()
    val downloadDanmuLiveData = MutableLiveData<LocalDanmuBean>()

    fun matchDanmu(videoSource: BaseVideoSource) {
        viewModelScope.launch(Dispatchers.IO) {
            if (videoSource.getMediaType() == MediaType.BILIBILI_STORAGE) {
                val parsed = BilibiliKeys.parse(videoSource.getUniqueKey())
                val cid = (parsed as? BilibiliKeys.ArchiveKey)?.cid
                if (cid != null) {
                    val library = DatabaseManager.instance.getMediaLibraryDao().getById(videoSource.getStorageId())
                    if (library != null) {
                        val storageKey = BilibiliPlaybackPreferencesStore.storageKey(library)
                        val danmu = BilibiliDanmakuDownloader.getOrDownload(storageKey, cid)
                        if (danmu != null) {
                            loadDanmuLiveData.postValue(videoSource.getVideoUrl() to danmu)
                        }
                    }
                }
                return@launch
            }

            DanmuSourceFactory
                .build(videoSource)
                ?.let {
                    DanmuFinder.instance.downloadMatched(it)
                }?.let {
                    loadDanmuLiveData.postValue(videoSource.getVideoUrl() to it)
                }
        }
    }

    fun searchDanmu(searchText: String) {
        if (searchText.isEmpty()) {
            return
        }

        viewModelScope.launch {
            val result = DanmuFinder.instance.search(searchText).flatMap { it.episodes }
            danmuSearchLiveData.postValue(result)
        }
    }

    fun downloadDanmu(episode: DanmuEpisodeData) {
        viewModelScope.launch {
            showLoading()
            val result =
                DanmuFinder.instance.downloadEpisode(episode) ?: run {
                    ToastCenter.showError("弹幕保存失败")
                    return@launch
                }
            hideLoading()

            downloadDanmuLiveData.postValue(result)
        }
    }
}
