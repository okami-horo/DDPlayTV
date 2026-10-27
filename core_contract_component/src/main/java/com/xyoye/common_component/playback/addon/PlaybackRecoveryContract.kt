package com.xyoye.common_component.playback.addon

data class PlaybackRecoveryRequest(
    val identity: PlaybackIdentity,
    val positionMs: Long,
    val playbackError: Throwable?,
    /**
     * 播放器侧可观测的诊断信息（来源可能包含 Media3/播放器内核）。
     * 仅传递事实，不在通用层固化“某个存储专属字段”。
     */
    val diagnostics: Map<String, String> = emptyMap()
)

interface PlaybackUrlRecoverableAddon : PlaybackAddon {
    /** Describes preparation only; the active session decides whether to acquire and commit a replacement. */
    suspend fun recover(request: PlaybackRecoveryRequest): Result<PlaybackReplacementDecision>
}

sealed interface PlaybackReplacementDecision {
    object Unavailable : PlaybackReplacementDecision

    object SavedForNextPlayback : PlaybackReplacementDecision

    class Prepare(
        val intent: PlaybackReplacementIntent
    ) : PlaybackReplacementDecision
}

/** Runtime-only binding. Neither protocol tokens nor recovery diagnostics belong in navigation or snapshots. */
class PlaybackReplacementIntent(
    val identity: PlaybackIdentity,
    val transportToken: String,
    val operation: PlaybackReplacementOperation
) {
    override fun toString(): String = "PlaybackReplacementIntent"
}

sealed interface PlaybackReplacementOperation {
    object ApplyPreferences : PlaybackReplacementOperation

    class Recover(
        val failingUrl: String?,
        val httpResponseCode: Int?,
        val isDecoderError: Boolean,
        val positionMs: Long
    ) : PlaybackReplacementOperation {
        override fun toString(): String = "Recover"
    }
}
