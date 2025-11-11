package com.xyoye.player_component.media3

import com.xyoye.common_component.network.repository.Media3TelemetrySink
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.RolloutToggleSnapshot

class NoOpTelemetrySink : Media3TelemetrySink {
    override suspend fun recordStartup(
        session: PlaybackSession,
        snapshot: RolloutToggleSnapshot?,
        autoplay: Boolean
    ) = Unit

    override suspend fun recordFirstFrame(session: PlaybackSession, latencyMs: Long) = Unit

    override suspend fun recordError(session: PlaybackSession, throwable: Throwable) = Unit

    override suspend fun recordCastTransfer(session: PlaybackSession, targetId: String?) = Unit
}
