package com.xyoye.player_component.playback.session

import android.content.Intent
import android.os.Bundle
import com.xyoye.common_component.playback.handoff.PlaybackNavigation

internal data class PlaybackActivityArguments(
    val requestId: String?,
    val recovery: PlaybackRecoveryContext?
) {
    companion object {
        fun read(
            intent: Intent?,
            savedState: Bundle?
        ): PlaybackActivityArguments {
            val requestId = intent?.getStringExtra(PlaybackNavigation.REQUEST_ID)?.takeIf(String::isNotBlank)
            val originalId = savedState?.getString(PlaybackNavigation.REQUEST_ID)?.takeIf(String::isNotBlank) ?: requestId
            val recovery =
                savedState?.let {
                    PlaybackRecoveryContext(it.getString(PlaybackNavigation.SESSION_ID)?.takeIf(String::isNotBlank), originalId)
                }
            return PlaybackActivityArguments(requestId, recovery)
        }

        fun save(
            outState: Bundle,
            requestId: String?,
            sessionId: String?
        ) {
            outState.putString(PlaybackNavigation.REQUEST_ID, requestId)
            outState.putString(PlaybackNavigation.SESSION_ID, sessionId)
        }
    }
}
