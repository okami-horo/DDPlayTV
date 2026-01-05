package com.xyoye.common_component.resolver

import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType

object PlaybackProfileResolver {
    @Suppress("UNUSED_PARAMETER")
    fun resolve(
        library: MediaLibraryEntity?,
        globalPlayerType: PlayerType,
        mediaType: MediaType,
    ): PlaybackProfile {
        val overrideValue = library?.playerTypeOverride ?: 0

        if (library == null || overrideValue == 0) {
            return PlaybackProfile(
                playerType = globalPlayerType,
                source = PlaybackProfileSource.GLOBAL,
            )
        }

        val overrideType = PlayerType.valueOf(overrideValue)
        if (overrideType.value != overrideValue) {
            return PlaybackProfile(
                playerType = globalPlayerType,
                source = PlaybackProfileSource.FALLBACK,
            )
        }

        return PlaybackProfile(
            playerType = overrideType,
            source = PlaybackProfileSource.LIBRARY_OVERRIDE,
        )
    }
}
