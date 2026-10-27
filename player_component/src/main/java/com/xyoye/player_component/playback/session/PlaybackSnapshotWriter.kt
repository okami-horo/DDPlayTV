package com.xyoye.player_component.playback.session

import com.xyoye.player_component.playback.handoff.PlaybackSnapshotTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Scope uses the playback dispatcher. Coroutine delay supplies a monotonic, testable schedule. */
internal class PlaybackSnapshotWriter(
    scope: CoroutineScope,
    private val capture: () -> PlaybackSnapshotTarget?,
    private val save: suspend (PlaybackSnapshotTarget) -> SnapshotSaveResult,
    private val onFailureNotice: () -> Unit
) {
    private val samples = Channel<PlaybackSnapshotTarget>(Channel.CONFLATED)
    private val worker =
        scope.launch {
            var notified = false
            for (sample in samples) {
                when (save(sample)) {
                    SnapshotSaveResult.SAVED -> notified = false
                    SnapshotSaveResult.STALE -> Unit
                    SnapshotSaveResult.FAILED ->
                        if (!notified) {
                            notified = true
                            onFailureNotice()
                        }
                }
            }
        }
    private val ticker =
        scope.launch {
            while (true) {
                delay(SAMPLE_INTERVAL_MS)
                requestSave()
            }
        }

    /** Pause, completed seek and background entry share this immediate-sampling path. */
    fun requestSave() {
        capture()?.let { samples.trySend(it) }
    }

    fun stop() {
        ticker.cancel()
        samples.cancel()
        worker.cancel()
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 5000L
    }
}

internal enum class SnapshotSaveResult { SAVED, FAILED, STALE }
