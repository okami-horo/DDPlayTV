package com.xyoye.player_component.ui.activities.player

import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.common_component.playback.addon.PlaybackRecoveryRequest
import com.xyoye.common_component.playback.addon.PlaybackSettingUpdate
import com.xyoye.common_component.playback.handoff.PlaybackAction
import com.xyoye.common_component.playback.handoff.PlaybackCommand
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.utils.DanmuUtils
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.SendDanmuBean
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
import master.flame.danmaku.danmaku.model.BaseDanmaku
import java.math.BigDecimal
import java.math.RoundingMode

class PlayerViewModel : BaseViewModel() {
    private val requestService = PlaybackRequestServiceImpl()
    internal val playbackState = requestService.observePlayback()
    private var retainedPlaybackSession: ActivePlaybackSession? = null
    private val _recoverySaveFailure = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recoverySaveFailure = _recoverySaveFailure.asSharedFlow()
    private val _playbackScreen = MutableStateFlow(PlaybackScreenState())
    internal val playbackScreen = _playbackScreen.asStateFlow()
    private var requestSequence = 0L
    private var pendingRequestId: String? = null
    private var verificationInFlight: String? = null
    internal val hasPlaybackSession get() = retainedPlaybackSession != null
    internal val activePlaybackSession get() = retainedPlaybackSession
    internal val playbackVersion get() = retainedPlaybackSession?.runtime?.version

    internal suspend fun applyPlaybackSetting(
        update: PlaybackSettingUpdate,
        expected: PlaybackSessionVersion
    ) = retainedPlaybackSession?.applySetting(update, expected) ?: SessionRequestResult.Ignored

    internal suspend fun recoverPlayback(
        request: PlaybackRecoveryRequest,
        expected: PlaybackSessionVersion
    ) = retainedPlaybackSession?.recover(request, expected) ?: SessionRequestResult.Ignored

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
        verificationInFlight = null
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
        pendingRequestId = null
        val sequence = ++requestSequence
        _playbackScreen.value = PlaybackScreenState(preparing = true, hasPlayback = true)
        viewModelScope.launch { publishResult(sequence, session.selectQueueItem(index, expected)) }
    }

    internal fun nativePlaybackTransition(candidateId: String) {
        val session = retainedPlaybackSession ?: return
        viewModelScope.launch { session.nativeTransition(candidateId) }
    }

    private fun publishResult(
        sequence: Long,
        result: SessionRequestResult
    ) {
        if (sequence != requestSequence) return
        pendingRequestId = null
        val failure =
            when (result) {
                is SessionRequestResult.Rejected -> result.failure
                is SessionRequestResult.BackendFailed -> PlaybackPreparationFailure.Unknown("BackendApplyFailed")
                is SessionRequestResult.Activated -> result.replacedRequestFailure
                SessionRequestResult.Ignored, SessionRequestResult.SettingsSaved -> null
            }
        _playbackScreen.value = PlaybackScreenState(hasPlayback = retainedPlaybackSession?.runtime != null, failure = failure)
    }

    internal fun acknowledgePlaybackFailure(failure: PlaybackPreparationFailure) {
        val state = _playbackScreen.value
        if (state.failure == failure) _playbackScreen.value = state.copy(failure = null)
    }

    internal fun beginVerification(id: String): Boolean {
        if (verificationInFlight == id) return false
        verificationInFlight = id
        return true
    }

    internal fun finishVerification(id: String?): Boolean {
        if (id == null || verificationInFlight != id) return false
        verificationInFlight = null
        return true
    }

    internal val awaitingVerification get() = verificationInFlight != null

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
                _playbackScreen.value = PlaybackScreenState(hasPlayback = true, failure = result.failure)
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

                TrackType.DANMU -> {
                    val danmu = track.type.getDanmu(track.trackResource)
                    if (danmu != null && danmu != videoSource.getDanmu()) {
                        videoSource.setDanmu(danmu)
                        historyDao.updateDanmu(uniqueKey, storageId, danmu.danmuPath, danmu.episodeId)
                    }
                }

                TrackType.VIDEO -> Unit
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

    fun sendDanmu(
        danmu: LocalDanmuBean?,
        sendDanmuBean: SendDanmuBean
    ) {
        val episodeId = danmu?.episodeId
        if (episodeId?.isNotEmpty() == true) {
            sendDanmuToServer(sendDanmuBean, episodeId)
        }

        val danmuPath = danmu?.danmuPath
        if (danmuPath?.isNotEmpty() == true) {
            writeDanmuToFile(sendDanmuBean, danmuPath)
        }
    }

    private fun sendDanmuToServer(
        sendDanmuBean: SendDanmuBean,
        episodeId: String
    ) {
        viewModelScope.launch {
            try {
                val time =
                    BigDecimal(sendDanmuBean.position.toDouble() / 1000)
                        .setScale(2, RoundingMode.HALF_UP)
                        .toString()

                val mode =
                    when {
                        sendDanmuBean.isScroll -> BaseDanmaku.TYPE_SCROLL_RL
                        sendDanmuBean.isTop -> BaseDanmaku.TYPE_FIX_TOP
                        else -> BaseDanmaku.TYPE_FIX_BOTTOM
                    }

                val color = sendDanmuBean.color and 0x00FFFFFF

                val result =
                    ResourceRepository.sendOneDanmu(
                        episodeId,
                        time,
                        mode,
                        color,
                        sendDanmuBean.text
                    )

                if (result.isFailure) {
                    val exception = result.exceptionOrNull()
                    val message = exception?.message.orEmpty()
                    ToastCenter.showOriginalToast("发送弹幕失败\n$message")

                    // 上报网络请求失败的异常
                    if (exception != null) {
                        ErrorReportHelper.postCatchedExceptionWithContext(
                            exception,
                            "PlayerViewModel",
                            "sendDanmuToServer",
                            "发送弹幕到服务器失败: $episodeId"
                        )
                    }
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "PlayerViewModel",
                    "sendDanmuToServer",
                    "发送弹幕异常: $episodeId"
                )
                ToastCenter.showOriginalToast("发送弹幕失败: ${e.message}")
            }
        }
    }

    private fun writeDanmuToFile(
        sendDanmuBean: SendDanmuBean,
        danmuPath: String
    ) {
        val time =
            BigDecimal(sendDanmuBean.position.toDouble() / 1000)
                .setScale(2, RoundingMode.HALF_UP)
                .toString()

        val mode =
            when {
                sendDanmuBean.isScroll -> BaseDanmaku.TYPE_SCROLL_RL
                sendDanmuBean.isTop -> BaseDanmaku.TYPE_FIX_TOP
                else -> BaseDanmaku.TYPE_FIX_BOTTOM
            }

        val unixTime = (System.currentTimeMillis() / 1000f).toInt().toString()
        val color = sendDanmuBean.color and 0x00FFFFFF

        val danmuText = "<d p=\"$time,$mode,25,$color,$unixTime,0,0,0\">${sendDanmuBean.text}</d>"

        DanmuUtils.appendDanmu(danmuPath, danmuText)
    }
}
