package com.xyoye.player_component.ui.activities.player

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.utils.danmu.DanmuFinder
import com.xyoye.common_component.utils.danmu.DanmuMatchCoordinator
import com.xyoye.common_component.utils.danmu.MatchOutcome
import com.xyoye.data_component.bean.DanmuTrackResource
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.player.controller.setting.DanmuPanelState
import com.xyoye.player.controller.setting.DanmuSearchState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PendingDanmu(
    val version: PlaybackSessionVersion,
    val requestId: Long,
    val resource: DanmuTrackResource,
    val manual: Boolean
)

class PlayerDanmuViewModel(
    private val finder: DanmuFinder = DanmuFinder.instance,
    private val match: suspend (BaseVideoSource) -> MatchOutcome = { source ->
        DanmuMatchCoordinator().match(source, DanmuMatchCoordinator.MatchMode.AUTO)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : BaseViewModel() {
    private val mutablePanelState = MutableLiveData(DanmuPanelState())
    val panelState: LiveData<DanmuPanelState> = mutablePanelState
    private val mutablePending = MutableLiveData<PendingDanmu?>()
    val pending: LiveData<PendingDanmu?> = mutablePending
    private var activeVersion: PlaybackSessionVersion? = null
    private var trackRequest = 0L
    private var searchRequest = 0L
    private var trackJob: Job? = null
    private var searchJob: Job? = null
    private var autoAttempted = false
    private var manualRequested = false

    fun activateSource(version: PlaybackSessionVersion) {
        if (activeVersion == version) return
        activeVersion = version
        trackRequest++
        searchRequest++
        trackJob?.cancel()
        searchJob?.cancel()
        mutablePending.value = null
        mutablePanelState.value = DanmuPanelState()
        autoAttempted = false
        manualRequested = false
    }

    fun matchDanmu(
        version: PlaybackSessionVersion,
        videoSource: BaseVideoSource
    ) {
        if (activeVersion != version || autoAttempted || manualRequested) return
        autoAttempted = true
        val request = ++trackRequest
        trackJob =
            viewModelScope.launch {
                try {
                    val outcome = withContext(ioDispatcher) { match(videoSource) }
                    if (isCurrent(version, request) && outcome is MatchOutcome.Success) {
                        mutablePending.value = PendingDanmu(version, request, outcome.resource, manual = false)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Automatic matching is optional; manual search remains available.
                }
            }
    }

    fun searchDanmu(text: String) {
        val query = text.trim()
        val request = ++searchRequest
        searchJob?.cancel()
        updatePanel { copy(search = if (query.isEmpty()) DanmuSearchState.Idle else DanmuSearchState.Loading(query)) }
        if (query.isEmpty()) return
        searchJob =
            viewModelScope.launch {
                val result =
                    try {
                        withContext(ioDispatcher) { finder.searchResult(query) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Result.failure(failure)
                    }
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                if (request != searchRequest) return@launch
                val state =
                    result.fold(
                        onSuccess = { animes ->
                            val episodes = animes.flatMap { it.episodes }
                            if (episodes.isEmpty()) DanmuSearchState.Empty(query) else DanmuSearchState.Content(query, episodes)
                        },
                        onFailure = { DanmuSearchState.Error(query, it.message ?: "弹幕搜索失败，请重试") }
                    )
                updatePanel { copy(search = state) }
            }
    }

    fun downloadDanmu(episode: DanmuEpisodeData) {
        val version = activeVersion ?: return
        manualRequested = true
        val request = ++trackRequest
        trackJob?.cancel()
        mutablePending.value = null
        updatePanel { copy(downloading = true, downloadError = null) }
        trackJob =
            viewModelScope.launch {
                try {
                    val resource = withContext(ioDispatcher) { finder.downloadEpisode(episode) }
                    if (!isCurrent(version, request)) return@launch
                    updatePanel { copy(downloading = false, downloadError = if (resource == null) "弹幕保存失败，请重试" else null) }
                    if (resource != null) {
                        mutablePending.value = PendingDanmu(version, request, DanmuTrackResource.LocalFile(resource), manual = true)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (isCurrent(
                            version,
                            request
                        )
                    ) {
                        updatePanel { copy(downloading = false, downloadError = failure.message ?: "弹幕下载失败，请重试") }
                    }
                }
            }
    }

    /** Main-thread take prevents a rebuilt Activity from replaying an already applied binding. */
    fun takePending(
        version: PlaybackSessionVersion,
        requestId: Long
    ): PendingDanmu? {
        val result = mutablePending.value ?: return null
        if (result.version != version || result.requestId != requestId || !isCurrent(version, requestId)) return null
        mutablePending.value = null
        return result
    }

    fun reportApplyFailure() {
        updatePanel { copy(downloading = false, downloadError = "弹幕加载失败，请重试") }
    }

    private fun isCurrent(
        version: PlaybackSessionVersion,
        request: Long
    ) = activeVersion == version && trackRequest == request

    private fun updatePanel(update: DanmuPanelState.() -> DanmuPanelState) {
        mutablePanelState.value = (mutablePanelState.value ?: DanmuPanelState()).update()
    }
}
