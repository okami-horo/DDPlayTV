package com.xyoye.local_component.ui.metadata

import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.metadata.MetadataCoordinator
import com.xyoye.common_component.metadata.MetadataPlaybackRequests
import com.xyoye.common_component.playback.PlaybackEntryRequests
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.MediaBinding
import com.xyoye.data_component.entity.MediaBindingEpisode
import com.xyoye.data_component.entity.MetadataWork
import com.xyoye.data_component.entity.PlayHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MetadataLibraryState(
    val works: List<MetadataWork> = emptyList(),
    val assets: List<MediaAsset> = emptyList(),
    val bindings: List<MediaBinding> = emptyList(),
    val episodes: List<MediaBindingEpisode> = emptyList(),
    val history: List<PlayHistoryEntity> = emptyList(),
    val loaded: Boolean = false
)

class MetadataLibraryViewModel : BaseViewModel() {
    val repository = MetadataCoordinator.repository
    private val dao = repository.dao
    val state =
        combine(
            dao.observeWorks(),
            dao.observeAssets(),
            dao.observeBindings(),
            dao.observeBindingEpisodes(),
            repository.database.getPlayHistoryDao().observeAll()
        ) { works, assets, bindings, episodes, history ->
            MetadataLibraryState(works, assets, bindings, episodes, history, loaded = true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MetadataLibraryState())
    private val playback = PlaybackEntryRequests(viewModelScope, { if (it) showLoading() else hideLoading() })
    private val playbackRequests = MetadataPlaybackRequests(repository)
    val playEvents = playback.outcomes

    fun play(history: PlayHistoryEntity) {
        playback.submit {
            PlaybackRequestInput(
                playbackRequests.history(history),
                null,
                PlaybackRequestOrigin.PLAY_HISTORY
            )
        }
    }

    fun play(
        asset: MediaAsset,
        season: Int? = null,
        episode: Int? = null
    ) {
        playback.submit {
            PlaybackRequestInput(
                playbackRequests.asset(asset.assetId, season, episode),
                null,
                PlaybackRequestOrigin.STORAGE_FILE
            )
        }
    }

    fun loadDetails(
        kind: String,
        id: Long
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repository.dao.work(kind, id) == null) runCatching { repository.details(kind, id) }
        }
    }
}
