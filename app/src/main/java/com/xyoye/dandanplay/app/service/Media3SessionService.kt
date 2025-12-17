package com.xyoye.dandanplay.app.service

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import com.xyoye.common_component.media3.Media3SessionClient
import com.xyoye.data_component.entity.media3.Media3BackgroundMode
import com.xyoye.data_component.entity.media3.PlaybackSession
import com.xyoye.data_component.entity.media3.PlayerCapabilityContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight background service that keeps Media3 session metadata in sync with MediaSession /
 * notification surfaces. The actual MediaSession wiring will happen in a follow-up once the
 * Media3 player stack is live; for now we expose StateFlows so activities/fragments react to
 * capability changes consistently.
 */
class Media3SessionService : LifecycleService() {
    private val sessionState = MutableStateFlow<PlaybackSession?>(null)
    private val capabilityState = MutableStateFlow<PlayerCapabilityContract?>(null)
    private val backgroundModesState = MutableStateFlow<Set<Media3BackgroundMode>>(emptySet())
    private val sessionCommandsState = MutableStateFlow<Set<String>>(emptySet())

    private val commandBridge =
        StateFlowCommandBridge(
            sessionCommandsState = sessionCommandsState,
            backgroundModesState = backgroundModesState,
        )

    private val coordinator = Media3BackgroundCoordinator(commandBridge)
    private val binder =
        Media3SessionBinder(
            sessionState = sessionState,
            capabilityState = capabilityState,
            backgroundModesState = backgroundModesState,
            sessionCommandsState = sessionCommandsState,
            coordinator = coordinator,
        )

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        coordinator.sync(null)
        sessionState.value = null
        capabilityState.value = null
        backgroundModesState.value = emptySet()
        sessionCommandsState.value = emptySet()
        super.onDestroy()
    }

    private class Media3SessionBinder(
        private val sessionState: MutableStateFlow<PlaybackSession?>,
        private val capabilityState: MutableStateFlow<PlayerCapabilityContract?>,
        private val backgroundModesState: MutableStateFlow<Set<Media3BackgroundMode>>,
        private val sessionCommandsState: MutableStateFlow<Set<String>>,
        private val coordinator: Media3BackgroundCoordinator
    ) : Binder(),
        Media3SessionClient {
        override fun updateSession(
            session: PlaybackSession?,
            capability: PlayerCapabilityContract?
        ) {
            sessionState.value = session
            capabilityState.value = capability
            coordinator.sync(capability)
        }

        override fun session(): StateFlow<PlaybackSession?> = sessionState

        override fun capability(): StateFlow<PlayerCapabilityContract?> = capabilityState

        override fun backgroundModes(): StateFlow<Set<Media3BackgroundMode>> = backgroundModesState

        override fun sessionCommands(): StateFlow<Set<String>> = sessionCommandsState
    }
}

private class StateFlowCommandBridge(
    private val sessionCommandsState: MutableStateFlow<Set<String>>,
    private val backgroundModesState: MutableStateFlow<Set<Media3BackgroundMode>>
) : MediaSessionCommandBridge {
    override fun updateSessionCommands(commands: Set<String>) {
        sessionCommandsState.value = commands
    }

    override fun updateBackgroundModes(modes: Set<Media3BackgroundMode>) {
        backgroundModesState.value = modes
    }
}
