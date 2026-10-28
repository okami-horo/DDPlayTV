package com.xyoye.player_component.service

import android.content.Context
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.playback.handoff.PlaybackCommand
import com.xyoye.common_component.playback.handoff.PlaybackDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackStateView
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import com.xyoye.common_component.source.handoff.PlaybackSourceResolver
import com.xyoye.player_component.playback.handoff.HandoffStoreResult
import com.xyoye.player_component.playback.handoff.PlaybackHandoffStore
import com.xyoye.player_component.playback.session.ActivePlaybackSession
import com.xyoye.player_component.playback.session.PlaybackSessionBackend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

@Route(path = RouteTable.Player.PlaybackRequestService, name = "Playback requests")
class PlaybackRequestServiceImpl internal constructor(
    private val store: PlaybackHandoffStore,
    private val resolve: suspend (PlaybackDescriptor) -> PlaybackResolutionResult
) : PlaybackRequestService {
    constructor() : this(sharedStore, resolve = { sharedResolver.resolve(it) })

    override fun init(context: Context?) = Unit

    override suspend fun submit(input: PlaybackRequestInput): PlaybackSubmissionResult =
        when (val result = store.submit(input)) {
            is HandoffStoreResult.Success -> PlaybackSubmissionResult.Accepted(result.value.requestId)
            is HandoffStoreResult.Failure -> PlaybackSubmissionResult.Rejected(PlaybackPreparationFailure.RecoveryStatePersistenceFailed)
        }

    internal fun createSession(
        backend: PlaybackSessionBackend,
        playbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): ActivePlaybackSession {
        val registration = Any()
        activeRegistration = registration
        state.value = null
        return ActivePlaybackSession(
            store,
            backend,
            resolve,
            playbackDispatcher,
            cleanupDispatcher,
            onStateChanged = { if (activeRegistration === registration) state.value = it },
            isCurrentOwner = { activeRegistration === registration }
        ).also { activeSession = WeakReference(it) }
    }

    override fun observePlayback(): StateFlow<PlaybackStateView?> = state.asStateFlow()

    override suspend fun dispatch(command: PlaybackCommand): PlaybackOperationResult =
        activeSession.get()?.dispatch(command)
            ?: PlaybackOperationResult.Rejected(PlaybackPreparationFailure.SessionSnapshotMissing)

    private companion object {
        val sharedStore by lazy { PlaybackHandoffStore() }
        val sharedResolver by lazy { PlaybackSourceResolver() }
        val state = MutableStateFlow<PlaybackStateView?>(null)

        // The ViewModel owns the runtime. The process provider must not keep its Activity/kernel alive.
        @Volatile var activeSession = WeakReference<ActivePlaybackSession>(null)

        @Volatile var activeRegistration: Any? = null
    }
}
