package com.xyoye.player_component.playback.session

import com.xyoye.common_component.playback.handoff.PlaybackAction
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.data_component.media3.entity.Media3BackgroundMode
import com.xyoye.data_component.media3.entity.Media3Capability
import com.xyoye.data_component.media3.entity.Media3PlaybackState

/** All calls run on the playback dispatcher. Kernels borrow sources; only the session retires them. */
internal interface PlaybackSessionBackend {
    fun apply(
        playback: PreparedPlayback,
        version: PlaybackSessionVersion,
        progress: PlaybackProgress = PlaybackProgress(playback.positionMs, playback.playWhenReady)
    )

    fun currentSource(): StorageVideoSource?

    /** Includes both current and queued items, including items retained after an apply failure. */
    fun isAttached(source: StorageVideoSource): Boolean

    fun progress(): PlaybackProgress

    fun positionConstraints(source: StorageVideoSource): PlaybackPositionConstraints

    /** Facts sampled from the attached kernel and local UI operations, never requested launch capabilities. */
    fun localState(): BackendPlaybackState = BackendPlaybackState()

    fun execute(action: PlaybackAction): Boolean = false

    fun enqueue(
        playback: PreparedPlayback,
        expected: PlaybackSessionVersion
    ): Boolean

    /** Check actual kernel state before removing a queued item; a delayed callback is not proof. */
    fun reconcileCandidate(playback: PreparedPlayback): QueuedPlaybackStatus

    /** Update borrowed metadata after an actual native transition; never restart playback. */
    fun adoptNative(
        playback: PreparedPlayback,
        version: PlaybackSessionVersion
    ) = Unit

    fun detach()
}

internal data class BackendPlaybackState(
    val state: Media3PlaybackState = Media3PlaybackState.INITIALIZING,
    val capabilities: Set<Media3Capability> = emptySet(),
    val backgroundModes: Set<Media3BackgroundMode> = emptySet()
)

internal enum class QueuedPlaybackStatus { DETACHED, CURRENT, STILL_ATTACHED }

internal data class PlaybackProgress(
    val positionMs: Long,
    val playWhenReady: Boolean
)

/** Unknown duration is not a reason to discard a valid saved position. Live/non-seekable media use zero. */
internal data class PlaybackPositionConstraints(
    val seekable: Boolean = true,
    val durationMs: Long? = null
) {
    fun normalize(positionMs: Long): Long {
        if (!seekable) return 0
        val position = positionMs.coerceAtLeast(0)
        return durationMs?.takeIf { it > 0 }?.let { position.coerceAtMost(it - 1) } ?: position
    }
}
