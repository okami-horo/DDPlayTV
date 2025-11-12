package com.xyoye.common_component.media3

import com.xyoye.data_component.entity.media3.Media3BackgroundMode
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface describing the interactions activities/fragments need from the Media3 session service.
 * Defined in common_component so feature modules can depend on it without referencing the app module.
 */
interface Media3SessionClient {
    fun updateSession(
        session: PlaybackSession?,
        capability: PlayerCapabilityContract?
    )

    fun session(): StateFlow<PlaybackSession?>

    fun capability(): StateFlow<PlayerCapabilityContract?>

    fun backgroundModes(): StateFlow<Set<Media3BackgroundMode>>

    fun sessionCommands(): StateFlow<Set<String>>
}
