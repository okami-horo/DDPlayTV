package com.xyoye.data_component.bean

import com.xyoye.data_component.enums.PlayerType

data class PlaybackProfile(
    val playerType: PlayerType,
    val source: PlaybackProfileSource,
)

enum class PlaybackProfileSource {
    GLOBAL,
    LIBRARY_OVERRIDE,
    FALLBACK,
}

