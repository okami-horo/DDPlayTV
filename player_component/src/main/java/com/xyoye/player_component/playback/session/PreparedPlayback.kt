package com.xyoye.player_component.playback.session

import com.xyoye.common_component.playback.handoff.PlayHistoryDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackLaunchOptions
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.playback.handoff.ScreencastDescriptor
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.common_component.source.handoff.ResolvedPlayback
import com.xyoye.player_component.playback.handoff.PlaybackSnapshotTarget
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Owns the resolved Source, never a second reference with independent lease-closing rights. */
internal class PreparedPlayback(
    val resolved: ResolvedPlayback,
    val launchOptions: PlaybackLaunchOptions?,
    val positionMs: Long = resolved.source.getCurrentPosition().coerceAtLeast(0),
    val playWhenReady: Boolean = launchOptions?.autoplay ?: true
) {
    val candidateId: String = UUID.randomUUID().toString()
    val source get() = resolved.source
    var runtimeProfile = source.getPlaybackProfile()
    val queueIndex: Int =
        resolved.queue.indexOfFirst { item ->
            val selected = resolved.descriptor
            if (selected is StoredLibraryFileDescriptor && item is StoredLibraryFileDescriptor) {
                item.libraryId == selected.libraryId && item.file.uniqueKey == selected.file.uniqueKey
            } else {
                item == selected
            }
        }
    private val ownership = AtomicInteger(PREPARED)

    init {
        if (queueIndex >= 0) {
            source.setQueueMetadata(
                queueIndex,
                resolved.queue.mapIndexed { index, item ->
                    if (index == queueIndex) {
                        resolved.identity.displayTitle
                    } else {
                        when (item) {
                            is StoredLibraryFileDescriptor -> item.file.displayTitle ?: item.file.uniqueKey
                            is ScreencastDescriptor -> item.relatedVideos.first { it.uniqueKey == item.playUniqueKey }.title
                            is PlayHistoryDescriptor -> item.expectedUniqueKey
                        }
                    }
                }
            )
        }
    }

    fun takeOwnership() {
        check(ownership.compareAndSet(PREPARED, OWNED)) { "Playback ownership already transferred or released" }
    }

    /** The session calls this only after confirmed backend detachment, on its cleanup dispatcher. */
    fun release() {
        if (ownership.getAndSet(RELEASED) != RELEASED) source.releasePlaybackResources()
    }

    fun snapshot(
        version: PlaybackSessionVersion,
        requestId: String
    ) = PlaybackSnapshotTarget(
        version,
        requestId,
        resolved.descriptor,
        launchOptions,
        positionMs,
        playWhenReady
    )

    private companion object {
        const val PREPARED = 0
        const val OWNED = 1
        const val RELEASED = 2
    }
}
