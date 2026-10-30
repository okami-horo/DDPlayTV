package com.xyoye.player.controller.setting

import com.xyoye.data_component.data.DanmuEpisodeData

sealed interface DanmuSearchState {
    object Idle : DanmuSearchState

    data class Loading(
        val query: String
    ) : DanmuSearchState

    data class Content(
        val query: String,
        val episodes: List<DanmuEpisodeData>
    ) : DanmuSearchState

    data class Empty(
        val query: String
    ) : DanmuSearchState

    data class Error(
        val query: String,
        val message: String
    ) : DanmuSearchState
}

data class DanmuPanelState(
    val search: DanmuSearchState = DanmuSearchState.Idle,
    val downloading: Boolean = false,
    val downloadError: String? = null
)
