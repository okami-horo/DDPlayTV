package com.xyoye.common_component.resolver

import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType

object PlaybackProfileResolver {
    fun resolve(
        library: MediaLibraryEntity?,
        globalPlayerType: PlayerType,
        mediaType: MediaType,
        supportedPlayerTypes: Set<PlayerType>,
        preferredPlayerType: PlayerType
    ): PlaybackProfile {
        val overrideValue = library?.playerTypeOverride ?: 0

        val (requestedType, requestedSource) =
            if (library == null || overrideValue == 0) {
                globalPlayerType to PlaybackProfileSource.GLOBAL
            } else {
                val overrideType = PlayerType.valueOf(overrideValue)
                if (overrideType.value != overrideValue) {
                    globalPlayerType to PlaybackProfileSource.FALLBACK
                } else {
                    overrideType to PlaybackProfileSource.LIBRARY_OVERRIDE
                }
            }

        val supported = supportedPlayerTypes.ifEmpty { setOf(preferredPlayerType) }
        if (requestedType in supported) {
            return PlaybackProfile(
                playerType = requestedType,
                source = requestedSource,
            )
        }

        val fallbackType = preferredPlayerType.takeIf { it in supported } ?: supported.first()
        LogFacade.w(
            LogModule.STORAGE,
            "playback_profile",
            "clamp playerType=$requestedType source=$requestedSource -> $fallbackType supported=$supported mediaType=$mediaType libraryId=${library?.id ?: -1}",
        )
        return PlaybackProfile(
            playerType = fallbackType,
            source = PlaybackProfileSource.FALLBACK,
        )
    }
}
