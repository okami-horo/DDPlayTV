package com.xyoye.player_component.ui.activities.player

import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.playback.handoff.PlaybackAction
import com.xyoye.common_component.playback.handoff.PlaybackCommand
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.playback.handoff.SeriesQueueFile
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.entity.DanmuBlockEntity
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player_component.playback.session.ActivePlaybackSession
import com.xyoye.player_component.playback.session.PlaybackActivityArguments
import com.xyoye.player_component.playback.session.PlaybackSessionBackend
import com.xyoye.player_component.playback.session.SessionRequestResult
import com.xyoye.player_component.service.PlaybackRequestServiceImpl
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PlayerViewModel : BaseViewModel() {
    private val danmuBindingWrites = Mutex()
    private val requestService = PlaybackRequestServiceImpl()
    internal val playbackState = requestService.observePlayback()
    private var retainedPlaybackSession: ActivePlaybackSession? = null
    private val _recoverySaveFailure = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recoverySaveFailure = _recoverySaveFailure.asSharedFlow()
    private val _playbackScreen = MutableStateFlow(PlaybackScreenState())
    internal val playbackScreen = _playbackScreen.asStateFlow()
    private var requestSequence = 0L
    private var pendingRequestId: String? = null
    internal val hasPlaybackSession get() = retainedPlaybackSession != null
    internal val activePlaybackSession get() = retainedPlaybackSession
    internal val playbackVersion get() = retainedPlaybackSession?.runtime?.version

    internal suspend fun fallbackPlaybackKernel(
        profile: PlaybackProfile,
        expected: PlaybackSessionVersion
    ) = retainedPlaybackSession?.fallbackKernel(expected, profile) ?: SessionRequestResult.Ignored

    internal val playbackSessionId get() =
        retainedPlaybackSession?.runtime?.version?.sessionId
            ?: retainedPlaybackSession?.committedTarget?.version?.sessionId

    internal fun bindPlayback(
        backend: PlaybackSessionBackend,
        arguments: PlaybackActivityArguments
    ) {
        val firstBinding = retainedPlaybackSession == null
        val session = playbackSession(backend)
        viewModelScope.launch {
            if (firstBinding) {
                pendingRequestId = arguments.requestId
                val sequence = ++requestSequence
                _playbackScreen.value = PlaybackScreenState(preparing = true)
                publishResult(sequence, session.open(arguments.requestId, arguments.recovery))
            } else {
                val result = session.bindBackend(backend)
                if (result is SessionRequestResult.BackendFailed || result is SessionRequestResult.Rejected) {
                    publishResult(requestSequence, result)
                }
            }
        }
    }

    internal fun deliverPlayback(requestId: String?) {
        val session = retainedPlaybackSession ?: return
        if (_playbackScreen.value.preparing && pendingRequestId == requestId) return
        pendingRequestId = requestId
        val sequence = ++requestSequence
        _playbackScreen.value = PlaybackScreenState(preparing = true, hasPlayback = session.runtime != null)
        viewModelScope.launch {
            val result =
                requestId?.let { session.deliver(it) }
                    ?: if (session.runtime != null) {
                        SessionRequestResult.Ignored
                    } else {
                        SessionRequestResult.Rejected(PlaybackPreparationFailure.RequestMissing)
                    }
            publishResult(sequence, result)
        }
    }

    internal fun selectQueueItem(index: Int) {
        val session = retainedPlaybackSession ?: return
        val expected = session.runtime?.version ?: return
        selectQueueItem(index, expected, null)
    }

    internal fun chooseQueueVersion(
        choice: QueueVersionChoice,
        file: SeriesQueueFile
    ) {
        if (_playbackScreen.value.versionChoice != choice || file !in choice.failure.options) return
        selectQueueItem(choice.index, choice.expected, file)
    }

    private fun selectQueueItem(
        index: Int,
        expected: PlaybackSessionVersion,
        file: SeriesQueueFile?
    ) {
        val session = retainedPlaybackSession ?: return
        pendingRequestId = null
        val sequence = ++requestSequence
        _playbackScreen.value = PlaybackScreenState(preparing = true, hasPlayback = true)
        viewModelScope.launch { publishResult(sequence, session.selectQueueItem(index, expected, file), expected) }
    }

    internal fun nativePlaybackTransition(candidateId: String) {
        val session = retainedPlaybackSession ?: return
        viewModelScope.launch { session.nativeTransition(candidateId) }
    }

    private fun publishResult(
        sequence: Long,
        result: SessionRequestResult,
        expected: PlaybackSessionVersion? = null
    ) {
        if (sequence != requestSequence) return
        pendingRequestId = null
        val failure =
            when (result) {
                is SessionRequestResult.Rejected -> result.failure
                is SessionRequestResult.BackendFailed -> PlaybackPreparationFailure.Unknown("BackendApplyFailed")
                is SessionRequestResult.Activated -> result.replacedRequestFailure
                SessionRequestResult.Ignored -> null
            }
        _playbackScreen.value =
            PlaybackScreenState(
                hasPlayback = retainedPlaybackSession?.runtime != null,
                failure = failure,
                versionChoice = versionChoice(failure, expected)
            )
    }

    private fun versionChoice(
        failure: PlaybackPreparationFailure?,
        expected: PlaybackSessionVersion?
    ): QueueVersionChoice? {
        if (failure !is PlaybackPreparationFailure.VersionSelectionRequired || expected == null) return null
        val active = retainedPlaybackSession?.runtime?.takeIf { it.version == expected } ?: return null
        val index =
            active.playback.resolved.queue
                .indexOf(failure.descriptor)
        return if (index >= 0) QueueVersionChoice(failure, expected, index) else null
    }

    internal fun acknowledgePlaybackFailure(failure: PlaybackPreparationFailure) {
        val state = _playbackScreen.value
        if (state.failure == failure) _playbackScreen.value = state.copy(failure = null, versionChoice = null)
    }

    internal fun unbindPlayback(backend: PlaybackSessionBackend) {
        val session = retainedPlaybackSession ?: return
        viewModelScope.launch(NonCancellable) { session.unbindBackend(backend) }
    }

    internal fun savePlaybackProgress() {
        retainedPlaybackSession?.requestProgressSave()
    }

    internal fun refreshPlaybackState(backend: PlaybackSessionBackend) {
        val session = retainedPlaybackSession ?: return
        val expected = session.runtime?.version ?: return
        viewModelScope.launch { session.refreshState(backend, expected) }
    }

    internal fun dispatchPlayback(action: PlaybackAction) {
        val expected = playbackVersion ?: return
        viewModelScope.launch {
            val result = requestService.dispatch(PlaybackCommand(expected, action))
            if (result is PlaybackOperationResult.Rejected && playbackVersion == expected) {
                _playbackScreen.value =
                    PlaybackScreenState(
                        hasPlayback = true,
                        failure = result.failure,
                        versionChoice = versionChoice(result.failure, expected)
                    )
            }
        }
    }

    internal fun exitPlayback() {
        val session = retainedPlaybackSession ?: return
        requestSequence++
        viewModelScope.launch(NonCancellable) {
            session.exit()
            _playbackScreen.value = PlaybackScreenState()
        }
    }

    internal fun playbackSession(backend: PlaybackSessionBackend): ActivePlaybackSession =
        retainedPlaybackSession ?: requestService.createSession(backend).also { session ->
            retainedPlaybackSession = session
            session.startSnapshotWriter(viewModelScope) { _recoverySaveFailure.tryEmit(Unit) }
        }

    override fun onCleared() {
        val session = retainedPlaybackSession
        retainedPlaybackSession = null
        if (session != null) viewModelScope.launch(NonCancellable) { session.exit() }
        super.onCleared()
    }

    val localDanmuBlockLiveData = DatabaseProvider.instance.getDanmuBlockDao().getAll(false)
    val cloudDanmuBlockLiveData = DatabaseProvider.instance.getDanmuBlockDao().getAll(true)

    fun storeTrackAdded(
        videoSource: BaseVideoSource,
        track: VideoTrackBean
    ) {
        val uniqueKey = videoSource.getUniqueKey()
        val storageId = videoSource.getStorageId()
        val historyDao = DatabaseProvider.instance.getPlayHistoryDao()

        if (track.type == TrackType.DANMU) {
            val danmu = track.type.getDanmu(track.trackResource) ?: return
            if (danmu == videoSource.getDanmu()) return
            videoSource.setDanmu(danmu)
            viewModelScope.launch {
                danmuBindingWrites.withLock {
                    if (videoSource.getDanmu() == danmu) {
                        historyDao.updateDanmu(uniqueKey, storageId, danmu.danmuPath, danmu.episodeId)
                    }
                }
            }
            return
        }

        viewModelScope.launch {
            when (track.type) {
                TrackType.AUDIO -> {
                    val audioPath = track.type.getAudio(track.trackResource)
                    if (audioPath != null && audioPath != videoSource.getAudioPath()) {
                        videoSource.setAudioPath(audioPath)
                        historyDao.updateAudio(uniqueKey, storageId, audioPath)
                    }
                }

                TrackType.SUBTITLE -> {
                    val subtitlePath = track.type.getSubtitle(track.trackResource)
                    if (subtitlePath != null && subtitlePath != videoSource.getSubtitlePath()) {
                        videoSource.setSubtitlePath(subtitlePath)
                        historyDao.updateSubtitle(uniqueKey, storageId, subtitlePath)
                    }
                }

                TrackType.DANMU, TrackType.VIDEO -> Unit
            }
        }
    }

    fun addDanmuBlock(
        keyword: String,
        isRegex: Boolean
    ) {
        viewModelScope.launch {
            DatabaseProvider.instance.getDanmuBlockDao().insert(
                DanmuBlockEntity(0, keyword, isRegex)
            )
        }
    }

    fun removeDanmuBlock(id: Int) {
        viewModelScope.launch {
            DatabaseProvider.instance.getDanmuBlockDao().delete(id)
        }
    }
}
