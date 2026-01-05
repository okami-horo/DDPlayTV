package com.xyoye.common_component.bilibili.playback
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayState
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object BilibiliPlaybackHeartbeat {
    private const val TAG_HEARTBEAT = "bilibili_heartbeat"

    private val reportIntervalMs: Long = TimeUnit.SECONDS.toMillis(5)
    private val failureLogIntervalMs: Long = TimeUnit.MINUTES.toMillis(1)

    private data class State(
        var lastSentAtMs: Long = 0L,
        var lastFailureLogAtMs: Long = 0L,
    )

    private val states = ConcurrentHashMap<BilibiliPlaybackSessionStore.Key, State>()

    fun clear(
        storageId: Int,
        uniqueKey: String,
    ) {
        states.remove(BilibiliPlaybackSessionStore.Key(storageId = storageId, uniqueKey = uniqueKey))
    }

    fun clearStorage(storageId: Int) {
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.storageId == storageId) {
                iterator.remove()
            }
        }
    }

    fun clearAll() {
        states.clear()
    }

    fun onProgress(
        storageId: Int,
        uniqueKey: String,
        mediaType: MediaType,
        positionMs: Long,
        isPlaying: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (mediaType != MediaType.BILIBILI_STORAGE) return
        if (!isPlaying) return

        val session = BilibiliPlaybackSessionStore.get(storageId, uniqueKey) ?: return
        val key = BilibiliPlaybackSessionStore.Key(storageId = storageId, uniqueKey = uniqueKey)

        val playedTimeSec = (positionMs / 1000L).coerceAtLeast(0L)
        if (playedTimeSec <= 0L) return

        val state = states.getOrPut(key) { State() }
        if (nowMs - state.lastSentAtMs < reportIntervalMs) return
        state.lastSentAtMs = nowMs

        send(session, key, playedTimeSec, state)
    }

    fun onPlayStateChanged(
        storageId: Int,
        uniqueKey: String,
        mediaType: MediaType,
        playState: PlayState,
        positionMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (mediaType != MediaType.BILIBILI_STORAGE) return

        val session = BilibiliPlaybackSessionStore.get(storageId, uniqueKey)
        val key = BilibiliPlaybackSessionStore.Key(storageId = storageId, uniqueKey = uniqueKey)
        when (playState) {
            PlayState.STATE_PAUSED -> {
                if (session == null) return
                val state = states.getOrPut(key) { State() }
                val playedTimeSec = (positionMs / 1000L).coerceAtLeast(0L)
                if (playedTimeSec <= 0L) return
                state.lastSentAtMs = nowMs
                send(session, key, playedTimeSec, state)
            }

            PlayState.STATE_COMPLETED -> {
                if (session == null) {
                    states.remove(key)
                    return
                }
                val state = states.getOrPut(key) { State() }
                state.lastSentAtMs = nowMs
                send(session, key, -1L, state)
                states.remove(key)
            }

            PlayState.STATE_IDLE,
            PlayState.STATE_START_ABORT,
            -> {
                states.remove(key)
            }

            else -> Unit
        }
    }

    private fun send(
        session: BilibiliPlaybackSession,
        key: BilibiliPlaybackSessionStore.Key,
        playedTimeSec: Long,
        state: State,
    ) {
        SupervisorScope.IO.launch {
            session
                .reportPlaybackHeartbeat(playedTimeSec)
                .onFailure { t ->
                    val failureNowMs = System.currentTimeMillis()
                    if (failureNowMs - state.lastFailureLogAtMs >= failureLogIntervalMs) {
                        state.lastFailureLogAtMs = failureNowMs
                        LogFacade.w(
                            LogModule.NETWORK,
                            TAG_HEARTBEAT,
                            "heartbeat failed storageId=${key.storageId} playedTimeSec=$playedTimeSec",
                            throwable = t,
                        )
                    }
                }
        }
    }
}
