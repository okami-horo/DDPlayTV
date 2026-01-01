package com.xyoye.data_component.entity.media3

data class Media3SessionBundle(
    val session: PlaybackSession,
    val capabilityContract: PlayerCapabilityContract,
    val toggleSnapshot: RolloutToggleSnapshot
)
