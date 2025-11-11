package com.xyoye.player_component.media3.session

import com.xyoye.common_component.config.Media3ToggleProvider
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * Ensures rollout evaluations remain immutable per playback session.
 * Evaluating the toggle returns the latest value, while binding persists the
 * snapshot against a sessionId so mid-session flips do not affect active playback.
 */
class RolloutSnapshotManager(
    private val resolver: () -> RolloutToggleSnapshot = { Media3ToggleProvider.snapshot() }
) {

    private val sessionSnapshots = ConcurrentHashMap<String, RolloutToggleSnapshot>()
    private var lastSnapshot: RolloutToggleSnapshot? = null

    fun evaluate(): RolloutToggleSnapshot {
        val snapshot = resolver()
        lastSnapshot = snapshot
        return snapshot
    }

    fun bind(sessionId: String, snapshot: RolloutToggleSnapshot) {
        val applied = if (snapshot.appliesToSession == sessionId) {
            snapshot
        } else {
            snapshot.copy(appliesToSession = sessionId)
        }
        sessionSnapshots[sessionId] = applied
        lastSnapshot = applied
    }

    fun snapshot(sessionId: String? = null): RolloutToggleSnapshot? {
        if (sessionId == null) {
            return lastSnapshot
        }
        return sessionSnapshots[sessionId]
    }

    fun clear(sessionId: String) {
        sessionSnapshots.remove(sessionId)
        if (lastSnapshot?.appliesToSession == sessionId) {
            lastSnapshot = null
        }
    }

    fun fallbackMessage(reason: String?): String {
        return reason?.takeIf { it.isNotBlank() }
            ?: "Media3 playback is unavailable for this session due to rollout safeguards."
    }
}
