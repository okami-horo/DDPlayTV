package com.xyoye.player_component.ui.activities.player

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.utils.danmu.DanmuMatchCoordinator
import com.xyoye.common_component.utils.danmu.DanmuFinder
import com.xyoye.common_component.utils.danmu.MatchOutcome
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.bean.DanmuTrackResource
import com.xyoye.data_component.data.DanmuEpisodeData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Created by xyoye on 2022/1/2.
 */

class PlayerDanmuViewModel : BaseViewModel() {
    val loadDanmuLiveData = MutableLiveData<Pair<String, DanmuTrackResource>>()
    val danmuSearchLiveData = MutableLiveData<List<DanmuEpisodeData>>()
    val downloadDanmuLiveData = MutableLiveData<DanmuTrackResource>()

    private val coordinator = DanmuMatchCoordinator()

    fun matchDanmu(videoSource: BaseVideoSource) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val outcome = coordinator.match(videoSource, DanmuMatchCoordinator.MatchMode.AUTO)) {
                is MatchOutcome.Success -> {
                    loadDanmuLiveData.postValue(videoSource.getVideoUrl() to outcome.resource)
                }
                is MatchOutcome.Candidates -> Unit
                is MatchOutcome.NotFound -> Unit
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

            downloadDanmuLiveData.postValue(DanmuTrackResource.LocalFile(result))
        }
    }
}
