package com.xyoye.common_component.storage.transport

import com.xyoye.common_component.bilibili.playback.BilibiliLivePlaybackSession
import com.xyoye.common_component.bilibili.playback.BilibiliLivePlaybackSessionStore
import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackSession
import com.xyoye.common_component.bilibili.playback.BilibiliPlaybackSessionStore
import com.xyoye.common_component.playback.addon.PlaybackReplacementIntent
import com.xyoye.common_component.playback.addon.PlaybackReplacementOperation
import com.xyoye.data_component.enums.PlayerType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class BilibiliPlaybackTransport private constructor(
    val sessionToken: String,
    playUrl: String,
    removeSession: () -> Unit
) : PlaybackTransportLease by ManagedPlaybackTransportLease(
        playUrl,
        PlaybackTransportType.BILIBILI,
        listOf(removeSession)
    ) {
    companion object {
        suspend fun acquireReplacement(
            intent: PlaybackReplacementIntent,
            playerType: PlayerType
        ): BilibiliPlaybackTransport {
            val identity = intent.identity
            val regular = BilibiliPlaybackSessionStore.get(identity.storageId, identity.uniqueKey, intent.transportToken)
            val recovery = intent.operation as? PlaybackReplacementOperation.Recover
            val failure =
                recovery?.let {
                    BilibiliPlaybackSession.FailureContext(it.failingUrl, it.httpResponseCode, it.isDecoderError)
                }
            if (regular != null) {
                val candidate = regular.fork(playerType)
                return acquire(candidate) {
                    when (intent.operation) {
                        PlaybackReplacementOperation.ApplyPreferences -> candidate.applySavedPreferences()
                        is PlaybackReplacementOperation.Recover -> candidate.recover(checkNotNull(failure), recovery.positionMs)
                    }.getOrThrow()
                }
            }
            val live =
                BilibiliLivePlaybackSessionStore.get(identity.storageId, identity.uniqueKey, intent.transportToken)
                    ?: throw TransportAcquisitionException(TransportAcquisitionFailure.SessionUnavailable)
            if (failure == null) throw TransportAcquisitionException(TransportAcquisitionFailure.UnsupportedTransport)
            val candidate = live.fork()
            return acquire(candidate) { candidate.recover(failure).getOrThrow() }
        }

        suspend fun acquire(
            session: BilibiliPlaybackSession,
            prepare: suspend () -> String
        ): BilibiliPlaybackTransport {
            val token = BilibiliPlaybackSessionStore.put(session)
            return prepareLease(token, prepare) {
                BilibiliPlaybackSessionStore.remove(session.storageId, session.uniqueKey, token)
            }
        }

        suspend fun acquire(
            session: BilibiliLivePlaybackSession,
            prepare: suspend () -> String
        ): BilibiliPlaybackTransport {
            val token = BilibiliLivePlaybackSessionStore.put(session)
            return prepareLease(token, prepare) {
                BilibiliLivePlaybackSessionStore.remove(session.storageId, session.uniqueKey, token)
            }
        }

        private suspend fun prepareLease(
            token: String,
            prepare: suspend () -> String,
            removeSession: () -> Unit
        ): BilibiliPlaybackTransport {
            var delivered = false
            try {
                val playUrl = prepare()
                currentCoroutineContext().ensureActive()
                val lease = BilibiliPlaybackTransport(token, playUrl, removeSession)
                delivered = true
                return lease
            } finally {
                if (!delivered) removeSession()
            }
        }
    }
}
