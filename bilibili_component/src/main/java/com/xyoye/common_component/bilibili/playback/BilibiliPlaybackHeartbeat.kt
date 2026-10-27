package com.xyoye.common_component.bilibili.playback

import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

object BilibiliPlaybackHeartbeat {
    private val dispatcher =
        BilibiliHeartbeatDispatcher(
            scope = SupervisorScope.IO,
            isActive = { BilibiliPlaybackSessionStore.get(it.storageId, it.uniqueKey, it.token) != null },
            report = { key, playedTimeSec, state ->
                val session = BilibiliPlaybackSessionStore.get(key.storageId, key.uniqueKey, key.token)
                if (session != null) {
                    val decision = session.heartbeatDecision()
                    coroutineContext.ensureActive()
                    val now = System.currentTimeMillis()
                    if (!decision.allowed) {
                        val reason = decision.skipReason?.logLabel.orEmpty()
                        if (reason.isNotBlank() && (reason != state.lastSkipReason || now - state.lastSkipLogAtMs >= 60_000)) {
                            state.lastSkipReason = reason
                            state.lastSkipLogAtMs = now
                            LogFacade.i(
                                LogModule.NETWORK,
                                "bilibili_heartbeat",
                                "heartbeat skipped storageId=${key.storageId} reason=$reason playedTimeSec=$playedTimeSec"
                            )
                        }
                    } else {
                        state.lastSkipReason = null
                        val result = session.reportPlaybackHeartbeat(playedTimeSec)
                        coroutineContext.ensureActive()
                        result.onFailure { failure ->
                            if (now - state.lastFailureLogAtMs >= 60_000) {
                                state.lastFailureLogAtMs = now
                                LogFacade.w(
                                    LogModule.NETWORK,
                                    "bilibili_heartbeat",
                                    "heartbeat failed storageId=${key.storageId} playedTimeSec=$playedTimeSec",
                                    throwable = failure
                                )
                            }
                        }
                    }
                }
            }
        )

    fun clear(
        storageId: Int,
        uniqueKey: String,
        token: String
    ) = dispatcher.clear(BilibiliPlaybackSessionStore.Key(storageId, uniqueKey, token))

    fun onProgress(
        storageId: Int,
        uniqueKey: String,
        token: String,
        mediaType: MediaType,
        positionMs: Long,
        isPlaying: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (mediaType == MediaType.BILIBILI_STORAGE && isPlaying && positionMs >= 1000) {
            dispatcher.send(BilibiliPlaybackSessionStore.Key(storageId, uniqueKey, token), positionMs / 1000, nowMs)
        }
    }

    fun onPlayStateChanged(
        storageId: Int,
        uniqueKey: String,
        token: String,
        mediaType: MediaType,
        playState: PlayState,
        positionMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (mediaType != MediaType.BILIBILI_STORAGE) return
        val key = BilibiliPlaybackSessionStore.Key(storageId, uniqueKey, token)
        when (playState) {
            PlayState.STATE_PAUSED -> if (positionMs >= 1000) dispatcher.send(key, positionMs / 1000, nowMs, force = true)
            PlayState.STATE_COMPLETED -> dispatcher.send(key, -1, nowMs, force = true)
            PlayState.STATE_IDLE, PlayState.STATE_START_ABORT -> dispatcher.clear(key)
            else -> Unit
        }
    }
}

/** The registry lock covers admission and retirement, never network I/O. */
internal class BilibiliHeartbeatDispatcher(
    private val scope: CoroutineScope,
    private val isActive: (BilibiliPlaybackSessionStore.Key) -> Boolean,
    private val report: suspend (BilibiliPlaybackSessionStore.Key, Long, State) -> Unit
) {
    internal class State(
        val job: Job
    ) {
        var lastSentAtMs: Long? = null
        var lastFailureLogAtMs: Long = 0
        var lastSkipLogAtMs: Long = 0
        var lastSkipReason: String? = null
    }

    private val states = mutableMapOf<BilibiliPlaybackSessionStore.Key, State>()

    fun send(
        key: BilibiliPlaybackSessionStore.Key,
        playedTimeSec: Long,
        nowMs: Long,
        force: Boolean = false
    ) {
        val state =
            synchronized(states) {
                if (!isActive(key)) return
                val state = states.getOrPut(key) { State(SupervisorJob(scope.coroutineContext[Job])) }
                if (!force && state.lastSentAtMs?.let { nowMs - it < 5000 } == true) return
                state.lastSentAtMs = nowMs
                state
            }
        // A concurrent clear cancels this parent even if launch has not run yet.
        CoroutineScope(scope.coroutineContext + state.job).launch { report(key, playedTimeSec, state) }
    }

    fun clear(key: BilibiliPlaybackSessionStore.Key) {
        val state = synchronized(states) { states.remove(key) }
        state?.job?.cancel()
    }
}
