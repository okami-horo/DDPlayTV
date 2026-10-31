package com.xyoye.anime_component.ui.activities.anime_history

import androidx.lifecycle.viewModelScope
import com.xyoye.anime_component.ui.activities.AnimeCollectionState
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.network.repository.AnimeRepository
import com.xyoye.data_component.data.AnimeData
import com.xyoye.data_component.data.CloudHistoryListData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AnimeHistoryViewModel(
    private val load: suspend () -> Result<CloudHistoryListData> = { AnimeRepository.getPlayHistory() }
) : BaseViewModel() {
    private val mutableState = MutableStateFlow(AnimeCollectionState())
    val state = mutableState.asStateFlow()
    private var initialized = false

    fun initialize(items: List<AnimeData>?) {
        if (initialized) return
        initialized = true
        if (items == null) getCloudHistory() else mutableState.update { it.copy(items = items) }
    }

    fun getCloudHistory() {
        if (state.value.loading) return
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = load()
            result.fold(
                onSuccess = { response ->
                    mutableState.update { it.copy(items = response.playHistoryAnimes, loading = false, error = null) }
                },
                onFailure = { error -> mutableState.update { it.copy(loading = false, error = error.message ?: "请检查连接后重试。") } }
            )
        }
    }

    fun searchAnime(keyword: String) {
        mutableState.update { it.copy(keyword = keyword) }
    }
}

internal fun filterAnimeHistory(
    histories: List<AnimeData>,
    searchWord: String
): List<AnimeData> = AnimeCollectionState(items = histories, keyword = searchWord).visibleItems
