package com.xyoye.player.subtitle.debug

import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.data_component.enums.SubtitleFallbackReason
import com.xyoye.data_component.enums.SurfaceType
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class PlaybackSessionStatus(
    val sessionId: String,
    val resolvedBackend: SubtitleRendererBackend,
    val videoSizePx: VideoSizePx,
    val surfaceType: SurfaceType,
    val fallbackTriggered: Boolean,
    val fallbackReasonCode: SubtitleFallbackReason?,
    val lastErrorMessage: String?,
    val startedAtEpochMs: Long,
    val firstRenderedAtEpochMs: Long?,
    val endedAtEpochMs: Long?
)

data class VideoSizePx(
    val width: Int,
    val height: Int
)

/**
 * Tracks the current playback session subtitle status for debugging/telemetry.
 */
object PlaybackSessionStatusProvider {
    private val statusRef: AtomicReference<PlaybackSessionStatus> =
        AtomicReference(
            PlaybackSessionStatus(
                sessionId = "",
                resolvedBackend = SubtitleRendererBackend.LIBASS,
                videoSizePx = VideoSizePx(0, 0),
                surfaceType = SurfaceType.VIEW_TEXTURE,
                fallbackTriggered = false,
                fallbackReasonCode = null,
                lastErrorMessage = null,
                startedAtEpochMs = 0L,
                firstRenderedAtEpochMs = null,
                endedAtEpochMs = null,
            ),
        )

    private fun updateStatus(transform: (PlaybackSessionStatus) -> PlaybackSessionStatus) {
        synchronized(statusRef) {
            statusRef.set(transform(statusRef.get()))
        }
    }

    fun startSession(
        backend: SubtitleRendererBackend,
        surfaceType: SurfaceType,
        width: Int,
        height: Int
    ) {
        val now = System.currentTimeMillis()
        statusRef.set(
            PlaybackSessionStatus(
                sessionId = UUID.randomUUID().toString(),
                resolvedBackend = backend,
                videoSizePx = VideoSizePx(width, height),
                surfaceType = surfaceType,
                fallbackTriggered = false,
                fallbackReasonCode = null,
                lastErrorMessage = null,
                startedAtEpochMs = now,
                firstRenderedAtEpochMs = null,
                endedAtEpochMs = null,
            ),
        )
    }

    fun updateSurface(surfaceType: SurfaceType) {
        updateStatus { current ->
            current.copy(surfaceType = surfaceType)
        }
    }

    fun updateFrameSize(
        width: Int,
        height: Int
    ) {
        updateStatus { current ->
            current.copy(videoSizePx = VideoSizePx(width, height))
        }
    }

    fun markFirstRender() {
        val now = System.currentTimeMillis()
        updateStatus { current ->
            if (current.firstRenderedAtEpochMs != null) {
                current
            } else {
                current.copy(firstRenderedAtEpochMs = now)
            }
        }
    }

    fun markFallback(
        reason: SubtitleFallbackReason,
        error: Throwable?
    ) {
        val message =
            error?.message
                ?: error?.javaClass?.simpleName
                ?: reason.name
        updateStatus { current ->
            current.copy(
                fallbackTriggered = true,
                fallbackReasonCode = reason,
                lastErrorMessage = message,
            )
        }
    }

    fun updateBackend(backend: SubtitleRendererBackend) {
        updateStatus { current ->
            current.copy(resolvedBackend = backend)
        }
    }

    fun endSession() {
        val now = System.currentTimeMillis()
        updateStatus { current ->
            current.copy(endedAtEpochMs = now)
        }
    }

    fun snapshot(): PlaybackSessionStatus = statusRef.get()
}
