package com.xyoye.player_component.playback.session

import com.xyoye.common_component.playback.handoff.PlayHistoryDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackAction
import com.xyoye.common_component.playback.handoff.PlaybackCommand
import com.xyoye.common_component.playback.handoff.PlaybackDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackOperationResult
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackQueueContext
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.playback.handoff.PlaybackStateView
import com.xyoye.common_component.playback.handoff.ScreencastDescriptor
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.common_component.playback.handoff.TorrentDescriptor
import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import com.xyoye.common_component.source.handoff.PlaybackSourceResolver
import com.xyoye.common_component.source.handoff.ResolvedPlayback
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.media3.entity.Media3Capability
import com.xyoye.player_component.playback.handoff.HandoffStoreFailureKind
import com.xyoye.player_component.playback.handoff.HandoffStoreResult
import com.xyoye.player_component.playback.handoff.PendingPlaybackRequest
import com.xyoye.player_component.playback.handoff.PlaybackHandoffStore
import com.xyoye.player_component.playback.handoff.PlaybackSnapshotRecord
import com.xyoye.player_component.playback.handoff.PlaybackSnapshotTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface SessionRequestResult {
    data class Activated(
        val version: PlaybackSessionVersion,
        val replacedRequestFailure: PlaybackPreparationFailure? = null,
        val cleanupFailed: Boolean = false
    ) : SessionRequestResult

    data class Rejected(
        val failure: PlaybackPreparationFailure,
        val cleanupFailed: Boolean = false
    ) : SessionRequestResult

    data class BackendFailed(
        val version: PlaybackSessionVersion
    ) : SessionRequestResult

    object Ignored : SessionRequestResult
}

internal data class ActivePlayback(
    val version: PlaybackSessionVersion,
    val requestId: String,
    val playback: PreparedPlayback
)

internal data class SessionExitResult(
    val detached: Boolean,
    val cleanupFailed: Boolean
)

/** Only create this from a non-null Android savedInstanceState, never an ordinary old Intent. */
internal data class PlaybackRecoveryContext(
    val sessionId: String?,
    val originalRequestId: String?
)

/** Sole mutation owner. Resolver work is cancellable; commit-through-apply is one ordered command. */
internal class ActivePlaybackSession(
    private val store: PlaybackHandoffStore,
    backend: PlaybackSessionBackend,
    private val resolve: suspend (PlaybackDescriptor) -> PlaybackResolutionResult = { PlaybackSourceResolver().resolve(it) },
    private val playbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onStateChanged: (PlaybackStateView?) -> Unit = {},
    private val isCurrentOwner: () -> Boolean = { true }
) {
    private val commands = Mutex()
    private var attachedBackend: PlaybackSessionBackend? = backend
    private val backend: PlaybackSessionBackend get() = checkNotNull(attachedBackend)
    private var backendReady = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var suspendedProgress: PlaybackProgress? = null
    private val owned = mutableSetOf<PreparedPlayback>()
    private var attempt: Preparation? = null
    private var nativePreparation: Job? = null
    private var candidate: PreparedPlayback? = null
    private var closed = false
    private var snapshotWriter: PlaybackSnapshotWriter? = null
    private var lastBackendState = BackendPlaybackState()

    private fun publishState() {
        val active = runtime
        if (closed || active == null) {
            onStateChanged(null)
            return
        }
        attachedBackend?.takeIf { it.currentSource() === active.playback.source }?.let {
            lastBackendState = it.localState()
        }
        onStateChanged(
            PlaybackStateView(
                active.version,
                active.playback.resolved.identity,
                lastBackendState.state,
                lastBackendState.capabilities,
                lastBackendState.backgroundModes,
                committedTarget?.version?.sourceRevision,
                recoverySaveFailed,
                active.playback.runtimeProfile.playerType
            )
        )
    }

    suspend fun refreshState(
        expectedBackend: PlaybackSessionBackend,
        expected: PlaybackSessionVersion
    ) = withContext(playbackDispatcher) {
        commands.withLock {
            if (!closed && isCurrentOwner() && attachedBackend === expectedBackend && runtime?.version == expected) publishState()
        }
    }

    suspend fun dispatch(command: PlaybackCommand): PlaybackOperationResult =
        withContext(playbackDispatcher) {
            val retired = mutableListOf<PreparedPlayback>()
            try {
                val rejected = PlaybackOperationResult.Rejected(PlaybackPreparationFailure.RequestSuperseded)
                val action = command.action
                if (action is PlaybackAction.SelectQueueItem) {
                    val index =
                        commands.withLock {
                            if (closed || !isCurrentOwner() || attachedBackend == null || !reconcileCandidate(retired)) return@withLock -1
                            val active = runtime
                            if (active?.version != command.expected) return@withLock -1
                            active.playback.resolved.queue.indexOfFirst {
                                val key =
                                    when (it) {
                                        is StoredLibraryFileDescriptor -> it.file.uniqueKey
                                        is PlayHistoryDescriptor -> it.expectedUniqueKey
                                        is TorrentDescriptor -> it.expectedUniqueKey
                                        is ScreencastDescriptor -> it.playUniqueKey
                                        else ->
                                            if (it ==
                                                active.playback.resolved.descriptor
                                            ) {
                                                active.playback.resolved.identity.uniqueKey
                                            } else {
                                                null
                                            }
                                    }
                                key == action.uniqueKey
                            }
                        }
                    if (index < 0) return@withContext rejected
                    return@withContext when (val result = selectQueueItem(index, command.expected)) {
                        is SessionRequestResult.Activated -> PlaybackOperationResult.Success
                        is SessionRequestResult.Rejected -> PlaybackOperationResult.Rejected(result.failure)
                        else -> PlaybackOperationResult.Rejected(PlaybackPreparationFailure.Unknown("BackendApplyFailed"))
                    }
                }
                commands.withLock {
                    if (closed || !isCurrentOwner() || attachedBackend == null) return@withLock rejected
                    if (backend.currentSource() !== runtime?.playback?.source && !reconcileCandidate(retired)) return@withLock rejected
                    if (runtime?.version != command.expected || backend.currentSource() !== runtime?.playback?.source) {
                        return@withLock rejected
                    }
                    val required =
                        when (action) {
                            PlaybackAction.Play -> Media3Capability.PLAY
                            PlaybackAction.Pause -> Media3Capability.PAUSE
                            is PlaybackAction.Seek -> Media3Capability.SEEK
                            is PlaybackAction.SetSpeed -> Media3Capability.SPEED
                            PlaybackAction.EnterBackground -> Media3Capability.BACKGROUND
                            PlaybackAction.EnterPictureInPicture -> Media3Capability.PIP
                            else -> null
                        }
                    if (required != null && required !in backend.localState().capabilities) {
                        return@withLock PlaybackOperationResult.Rejected(PlaybackPreparationFailure.UnsupportedSource)
                    }
                    if ((action is PlaybackAction.Seek && action.positionMs < 0) ||
                        (action is PlaybackAction.SetSpeed && (!action.speed.isFinite() || action.speed !in 0.25f..4f))
                    ) {
                        return@withLock PlaybackOperationResult.Rejected(PlaybackPreparationFailure.InvalidDescriptor)
                    }
                    val applied = runCatching { backend.execute(action) }.getOrDefault(false)
                    publishState()
                    if (applied) {
                        if (action is PlaybackAction.Seek || action == PlaybackAction.Pause) requestProgressSave()
                        PlaybackOperationResult.Success
                    } else {
                        PlaybackOperationResult.Rejected(PlaybackPreparationFailure.UnsupportedSource)
                    }
                }
            } finally {
                withContext(NonCancellable + cleanupDispatcher) { retired.forEach(PreparedPlayback::release) }
            }
        }

    // Read on the playback dispatcher. A failed apply or native save may put these at different revisions.
    var runtime: ActivePlayback? = null
        private set
    var committedTarget: PlaybackSnapshotTarget? = null
        private set
    var recoverySaveFailed: Boolean = false
        private set

    private val version: PlaybackSessionVersion?
        get() = listOfNotNull(runtime?.version, committedTarget?.version).maxByOrNull { it.sourceRevision }

    /** Drop the old Activity/kernel reference while retaining the session's live source and position. */
    suspend fun unbindBackend(expected: PlaybackSessionBackend): Boolean =
        withContext(NonCancellable + playbackDispatcher) {
            val retired = mutableListOf<PreparedPlayback>()
            try {
                commands.withLock {
                    if (attachedBackend !== expected) return@withLock true
                    detachForRecreation(retired)
                }
            } finally {
                withContext(cleanupDispatcher) { retired.forEach(PreparedPlayback::release) }
            }
        }

    suspend fun bindBackend(next: PlaybackSessionBackend): SessionRequestResult =
        withContext(NonCancellable + playbackDispatcher) {
            val retired = mutableListOf<PreparedPlayback>()
            try {
                commands.withLock {
                    if (closed) return@withLock SessionRequestResult.Ignored
                    if (attachedBackend === next) return@withLock SessionRequestResult.Ignored
                    if (attachedBackend != null && !detachForRecreation(retired)) {
                        return@withLock SessionRequestResult.Rejected(PlaybackPreparationFailure.Unknown("BackendDetachFailed"))
                    }
                    attachedBackend = next
                    backendReady.complete(Unit)
                    val active = runtime ?: return@withLock SessionRequestResult.Ignored
                    val progress = suspendedProgress ?: PlaybackProgress(active.playback.positionMs, active.playback.playWhenReady)
                    val applied = runCatching { next.apply(active.playback, active.version, progress) }.isSuccess
                    suspendedProgress = null
                    publishState()
                    if (applied && next.currentSource() === active.playback.source) {
                        SessionRequestResult.Activated(active.version)
                    } else {
                        SessionRequestResult.BackendFailed(active.version)
                    }
                }
            } finally {
                withContext(cleanupDispatcher) { retired.forEach(PreparedPlayback::release) }
            }
        }

    private suspend fun detachForRecreation(retired: MutableList<PreparedPlayback>): Boolean {
        val current = attachedBackend ?: return true
        nativePreparation?.cancel()
        reconcileCandidate(retired)
        suspendedProgress = current.progress()
        lastBackendState = current.localState()
        if (runCatching { current.detach() }.isFailure) return false
        if (owned.any { current.isAttached(it.source) }) return false
        attachedBackend = null
        backendReady = CompletableDeferred()
        candidate = null
        collectDetached(retired)
        return true
    }

    /** Wait without owning the command lock, so recreation and explicit exit can always proceed. */
    private suspend fun <T> withAttachedBackend(block: suspend () -> T): T {
        while (true) {
            backendReady.await()
            commands.lock()
            try {
                if (attachedBackend != null || closed) return block()
            } finally {
                commands.unlock()
            }
        }
    }

    suspend fun deliver(requestId: String): SessionRequestResult =
        withContext(playbackDispatcher) {
            val retired = mutableListOf<PreparedPlayback>()
            var preparing: Preparation? = null
            var request: PendingPlaybackRequest? = null
            var prepared: PreparedPlayback? = null
            var transferred = false
            try {
                val start =
                    commands.withLock {
                        if (closed || attempt?.requestId == requestId || committedTarget?.requestId == requestId) {
                            return@withLock SessionRequestResult.Ignored
                        }
                        val pending = store.readPending(requestId)
                        if (pending is HandoffStoreResult.Failure) return@withLock reject(requestId, pending)
                        if (!reconcileCandidate(retired)) {
                            return@withLock reject(requestId, PlaybackPreparationFailure.Unknown("BackendQueueAttached"))
                        }
                        attempt?.job?.cancel()
                        nativePreparation?.cancel()
                        request = (pending as HandoffStoreResult.Success).value
                        preparing = Preparation(requestId, version, currentCoroutineContext().job)
                        attempt = preparing
                        null
                    }
                if (start != null) return@withContext start
                val current = checkNotNull(preparing)
                val input = checkNotNull(request).input
                when (val result = resolve(input.descriptor)) {
                    is PlaybackResolutionResult.Failure -> return@withContext reject(requestId, result.failure)
                    is PlaybackResolutionResult.Success -> {
                        try {
                            prepared = PreparedPlayback(result.playback, input.launchOptions)
                        } finally {
                            if (prepared == null) {
                                withContext(NonCancellable + cleanupDispatcher) {
                                    result.playback.source.releasePlaybackResources()
                                }
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                // Once entered, later requests and caller cancellation cannot abandon a successful disk commit.
                withContext(NonCancellable) {
                    withAttachedBackend commit@{
                        if (closed || attempt !== current || !reconcileCandidate(retired) || version != current.expected) {
                            return@commit reject(requestId, PlaybackPreparationFailure.RequestSuperseded)
                        }
                        val next = checkNotNull(prepared)
                        val target = next.snapshot(nextVersion(requestId), requestId)
                        when (
                            val saved =
                                store.activate(
                                    requestId,
                                    target
                                ) { !closed && attempt === current && version == current.expected }
                        ) {
                            is HandoffStoreResult.Failure -> reject(requestId, saved)
                            is HandoffStoreResult.Success -> {
                                committedTarget = saved.value.target
                                recoverySaveFailed = false
                                next.takeOwnership()
                                owned.add(next)
                                transferred = true
                                attempt = null
                                applyToBackend(next, target, retired)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { store.clearPending(requestId) }
                throw cancelled
            } catch (failure: Exception) {
                reject(requestId, PlaybackPreparationFailure.Unknown(failure.javaClass.simpleName))
            } finally {
                withContext(NonCancellable) {
                    commands.withLock { if (attempt === preparing) attempt = null }
                    withContext(cleanupDispatcher) {
                        if (!transferred) prepared?.release()
                        retired.forEach(PreparedPlayback::release)
                    }
                }
            }
        }

    suspend fun open(
        requestId: String?,
        recovery: PlaybackRecoveryContext?
    ): SessionRequestResult =
        withContext(playbackDispatcher) {
            if (runtime != null) return@withContext SessionRequestResult.Ignored
            val saved = recovery?.let { store.readSnapshotForRecovery(it.sessionId, it.originalRequestId) }
            if (saved is HandoffStoreResult.Success) {
                commands.withLock {
                    // A new pending request replaces this bound session even when its old kernel died with the process.
                    if (!closed && attempt == null && committedTarget == null) committedTarget = saved.value.target
                }
            }
            val result = requestId?.let { deliver(it) }
            if (result is SessionRequestResult.Activated || result is SessionRequestResult.BackendFailed || closed) {
                return@withContext result ?: SessionRequestResult.Ignored
            }
            if (recovery == null) return@withContext result ?: SessionRequestResult.Rejected(PlaybackPreparationFailure.RequestMissing)
            if (saved is HandoffStoreResult.Failure) return@withContext SessionRequestResult.Rejected(snapshotFailure(saved))
            val restored = restore((saved as HandoffStoreResult.Success).value)
            if (restored is SessionRequestResult.Activated && result is SessionRequestResult.Rejected) {
                restored.copy(
                    replacedRequestFailure = result.failure.takeUnless { it == PlaybackPreparationFailure.RequestMissing },
                    cleanupFailed = result.cleanupFailed
                )
            } else {
                restored
            }
        }

    private suspend fun restore(saved: PlaybackSnapshotRecord): SessionRequestResult {
        val target = saved.target
        val retired = mutableListOf<PreparedPlayback>()
        var prepared: PreparedPlayback? = null
        var transferred = false
        val current =
            commands.withLock {
                if (closed || runtime != null || attempt != null) return SessionRequestResult.Ignored
                Preparation(target.requestId, version, currentCoroutineContext().job).also { attempt = it }
            }
        try {
            when (val result = resolve(target.descriptor)) {
                is PlaybackResolutionResult.Failure -> return SessionRequestResult.Rejected(result.failure)
                is PlaybackResolutionResult.Success -> {
                    try {
                        val position =
                            (
                                attachedBackend?.positionConstraints(
                                    result.playback.source
                                ) ?: PlaybackPositionConstraints()
                            ).normalize(target.positionMs)
                        prepared = PreparedPlayback(result.playback, target.launchOptions, position, target.playWhenReady)
                    } finally {
                        if (prepared == null) {
                            withContext(NonCancellable + cleanupDispatcher) {
                                result.playback.source.releasePlaybackResources()
                            }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) {
                withAttachedBackend commit@{
                    if (closed || attempt !== current || version != current.expected) {
                        return@commit SessionRequestResult.Rejected(PlaybackPreparationFailure.RequestSuperseded)
                    }
                    // Recheck binding/expiry after I/O; a restoration never consumes unrelated pending.
                    val latest = store.readSnapshot(target.version.sessionId)
                    if (latest is HandoffStoreResult.Failure) return@commit SessionRequestResult.Rejected(snapshotFailure(latest))
                    if ((latest as HandoffStoreResult.Success).value.target != target) {
                        return@commit SessionRequestResult.Rejected(PlaybackPreparationFailure.RequestSuperseded)
                    }
                    val next = checkNotNull(prepared)
                    next.takeOwnership()
                    owned.add(next)
                    transferred = true
                    committedTarget = target
                    attempt = null
                    applyToBackend(next, target, retired)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return SessionRequestResult.Rejected(PlaybackPreparationFailure.Unknown(failure.javaClass.simpleName))
        } finally {
            withContext(NonCancellable) {
                commands.withLock { if (attempt === current) attempt = null }
                withContext(cleanupDispatcher) {
                    if (!transferred) prepared?.release()
                    retired.forEach(PreparedPlayback::release)
                }
            }
        }
    }

    fun startSnapshotWriter(
        scope: CoroutineScope,
        onFailureNotice: () -> Unit
    ) {
        check(snapshotWriter == null)
        snapshotWriter = PlaybackSnapshotWriter(scope, ::captureProgress, ::saveProgress, onFailureNotice)
    }

    fun requestProgressSave() {
        snapshotWriter?.requestSave()
    }

    /** Called on the playback thread; sampling stays responsive while a previous write runs on IO. */
    fun captureProgress(): PlaybackSnapshotTarget? {
        val active = runtime ?: return null
        val current = attachedBackend
        if (closed || (current != null && current.currentSource() !== active.playback.source) || version != active.version) return null
        val progress = current?.progress() ?: suspendedProgress ?: return null
        return active.playback.snapshot(active.version, active.requestId).copy(
            positionMs = progress.positionMs.coerceAtLeast(0),
            playWhenReady = progress.playWhenReady
        )
    }

    suspend fun saveProgress(target: PlaybackSnapshotTarget): SnapshotSaveResult =
        withContext(NonCancellable + playbackDispatcher) {
            commands.withLock {
                if (closed || runtime?.version != target.version || version != target.version) return@withLock SnapshotSaveResult.STALE
                when (val saved = store.saveSnapshot(target) { !closed && runtime?.version == it && version == it }) {
                    is HandoffStoreResult.Success -> {
                        committedTarget = saved.value.target
                        recoverySaveFailed = false
                        publishState()
                        SnapshotSaveResult.SAVED
                    }
                    is HandoffStoreResult.Failure -> {
                        recoverySaveFailed = true
                        publishState()
                        SnapshotSaveResult.FAILED
                    }
                }
            }
        }

    private fun applyToBackend(
        next: PreparedPlayback,
        target: PlaybackSnapshotTarget,
        retired: MutableList<PreparedPlayback>,
        progress: PlaybackProgress = PlaybackProgress(next.positionMs, next.playWhenReady)
    ): SessionRequestResult {
        val applied = runCatching { backend.apply(next, target.version, progress) }.isSuccess
        val actual = backend.currentSource()
        if (actual === next.source) {
            runtime = ActivePlayback(target.version, target.requestId, next)
        } else if (actual !== runtime?.playback?.source) {
            runtime = null
        }
        collectDetached(retired)
        publishState()
        return if (applied && actual === next.source) {
            SessionRequestResult.Activated(target.version)
        } else {
            SessionRequestResult.BackendFailed(target.version)
        }
    }

    private fun snapshotFailure(result: HandoffStoreResult.Failure): PlaybackPreparationFailure =
        when (result.failure.kind) {
            HandoffStoreFailureKind.SENSITIVE_PAYLOAD_UNAVAILABLE -> PlaybackPreparationFailure.SensitivePayloadUnavailable
            else -> PlaybackPreparationFailure.SessionSnapshotMissing
        }

    /** Controlled internal selection retains the session/request identity and never consumes external pending. */
    suspend fun selectQueueItem(
        index: Int,
        expected: PlaybackSessionVersion
    ): SessionRequestResult =
        withContext(playbackDispatcher) {
            val retired = mutableListOf<PreparedPlayback>()
            var preparing: Preparation? = null
            var prepared: PreparedPlayback? = null
            var transferred = false
            try {
                val active =
                    commands.withLock {
                        if (closed || !reconcileCandidate(retired) || runtime?.version != expected) return@withLock null
                        val active = runtime ?: return@withLock null
                        if (index !in active.playback.resolved.queue.indices) return@withLock null
                        attempt?.job?.cancel()
                        nativePreparation?.cancel()
                        preparing = Preparation(null, expected, currentCoroutineContext().job).also { attempt = it }
                        active
                    } ?: return@withContext SessionRequestResult.Rejected(PlaybackPreparationFailure.RequestSuperseded)
                val resolution = resolve(active.playback.resolved.queue[index])
                when (val result = resolution) {
                    is PlaybackResolutionResult.Failure -> return@withContext SessionRequestResult.Rejected(result.failure)
                    is PlaybackResolutionResult.Success -> {
                        prepared = prepareQueueItem(active.playback, result.playback)
                    }
                }
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    withAttachedBackend commit@{
                        if (closed || attempt !== preparing || !reconcileCandidate(retired) || runtime?.version != expected) {
                            return@commit SessionRequestResult.Rejected(PlaybackPreparationFailure.RequestSuperseded)
                        }
                        val next = checkNotNull(prepared)
                        val target = next.snapshot(nextVersion(active.version.sessionId), active.requestId)
                        val saved =
                            store.saveSnapshot(target) {
                                !closed && attempt === preparing && runtime?.version == expected && it == target.version
                            }
                        if (saved is HandoffStoreResult.Failure) {
                            return@commit SessionRequestResult.Rejected(PlaybackPreparationFailure.RecoveryStatePersistenceFailed)
                        }
                        committedTarget = (saved as HandoffStoreResult.Success).value.target
                        recoverySaveFailed = false
                        next.takeOwnership()
                        owned.add(next)
                        transferred = true
                        attempt = null
                        applyToBackend(next, target, retired, PlaybackProgress(target.positionMs, target.playWhenReady))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SessionRequestResult.Rejected(PlaybackPreparationFailure.Unknown(failure.javaClass.simpleName))
            } finally {
                withContext(NonCancellable) {
                    commands.withLock { if (attempt === preparing) attempt = null }
                    withContext(cleanupDispatcher) {
                        if (!transferred) prepared?.release()
                        retired.forEach(PreparedPlayback::release)
                    }
                }
            }
        }

    /** Kernel-only retry borrows the same transport and preserves identity/revision and global settings. */
    suspend fun fallbackKernel(
        expected: PlaybackSessionVersion,
        profile: PlaybackProfile
    ): SessionRequestResult =
        withContext(NonCancellable + playbackDispatcher) {
            val retired = mutableListOf<PreparedPlayback>()
            try {
                commands.withLock {
                    if (closed || attachedBackend == null || !reconcileCandidate(retired) || runtime?.version != expected) {
                        return@withLock SessionRequestResult.Rejected(PlaybackPreparationFailure.RequestSuperseded)
                    }
                    attempt?.job?.cancel()
                    nativePreparation?.cancel()
                    attempt = null
                    val active = checkNotNull(runtime)
                    val progress = backend.progress()
                    active.playback.runtimeProfile = profile
                    val target =
                        active.playback.snapshot(active.version, active.requestId).copy(
                            positionMs = progress.positionMs,
                            playWhenReady = true
                        )
                    applyToBackend(active.playback, target, retired, PlaybackProgress(target.positionMs, target.playWhenReady))
                }
            } finally {
                withContext(cleanupDispatcher) { retired.forEach(PreparedPlayback::release) }
            }
        }

    /** Resolves stable queue entries; neither the browser nor the coordinator constructs a Source. */
    suspend fun prepareNextNative(expected: PlaybackSessionVersion): Boolean =
        withContext(playbackDispatcher) {
            val job = currentCoroutineContext().job
            val active =
                commands.withLock {
                    val active = runtime ?: return@withLock null
                    if (closed ||
                        attachedBackend == null ||
                        active.version != expected ||
                        attempt != null ||
                        candidate != null ||
                        nativePreparation != null ||
                        active.playback.queueIndex + 1 !in active.playback.resolved.queue.indices
                    ) {
                        return@withLock null
                    }
                    nativePreparation = job
                    active
                } ?: return@withContext false
            var prepared: PreparedPlayback? = null
            var handedOver = false
            try {
                val descriptor = active.playback.resolved.queue[active.playback.queueIndex + 1]
                val result = resolve(descriptor)
                if (result !is PlaybackResolutionResult.Success) return@withContext false
                prepared = prepareQueueItem(active.playback, result.playback)
                currentCoroutineContext().ensureActive()
                handedOver = true
                queueNative(expected, prepared)
            } finally {
                withContext(NonCancellable) {
                    commands.withLock { if (nativePreparation === job) nativePreparation = null }
                    if (!handedOver) withContext(cleanupDispatcher) { prepared?.release() }
                }
            }
        }

    private suspend fun prepareQueueItem(
        previous: PreparedPlayback,
        resolved: ResolvedPlayback
    ): PreparedPlayback {
        try {
            val selected = resolved.descriptor
            val context = (previous.resolved.descriptor as? StoredLibraryFileDescriptor)?.queueContext
            val descriptor =
                if (selected is StoredLibraryFileDescriptor && context != null) {
                    val files =
                        previous.resolved.queue
                            .filterIsInstance<StoredLibraryFileDescriptor>()
                            .map { it.file }
                    val index = files.indexOfFirst { it.uniqueKey == selected.file.uniqueKey }
                    val size = PlaybackQueueContext.MAX_FALLBACK_ITEMS
                    val start = (index - size / 2).coerceIn(0, (files.size - size).coerceAtLeast(0))
                    selected.copy(
                        queueContext =
                            context.copy(
                                selectedUniqueKey = selected.file.uniqueKey,
                                fallbackItems = files.drop(start).take(size)
                            )
                    )
                } else {
                    selected
                }
            val queued = ResolvedPlayback(resolved.source, descriptor, resolved.identity, previous.resolved.queue, resolved.downloadAdvice)
            return PreparedPlayback(queued, previous.launchOptions?.copy(mediaId = resolved.identity.mediaId, autoplay = true))
        } catch (failure: Throwable) {
            withContext(NonCancellable + cleanupDispatcher) { resolved.source.releasePlaybackResources() }
            throw failure
        }
    }

    /** Consumes the prepared owner even when rejected. Only session preparation supplies production candidates. */
    suspend fun queueNative(
        expected: PlaybackSessionVersion,
        next: PreparedPlayback
    ): Boolean {
        // Own the handover before dispatch: prompt cancellation can prevent the target block from running.
        next.takeOwnership()
        val retired = mutableListOf<PreparedPlayback>()
        var transferred = false
        try {
            return withContext(playbackDispatcher) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    commands.withLock {
                        if (closed ||
                            attachedBackend == null ||
                            attempt != null ||
                            version != expected ||
                            runtime?.version != expected ||
                            candidate != null
                        ) {
                            return@withLock false
                        }
                        owned.add(next)
                        transferred = true
                        candidate = next
                        val queued = runCatching { backend.enqueue(next, expected) }.getOrDefault(false)
                        if (!queued) reconcileCandidate(retired)
                        collectDetached(retired)
                        queued
                    }
                }
            }
        } finally {
            withContext(NonCancellable + cleanupDispatcher) {
                if (!transferred) next.release()
                retired.forEach(PreparedPlayback::release)
            }
        }
    }

    suspend fun nativeTransition(candidateId: String) =
        withContext(NonCancellable + playbackDispatcher) {
            val retired = mutableListOf<PreparedPlayback>()
            try {
                commands.withLock {
                    if (!closed && candidate?.candidateId == candidateId) reconcileCandidate(retired)
                }
            } finally {
                withContext(cleanupDispatcher) { retired.forEach(PreparedPlayback::release) }
            }
        }

    /** Stale backend errors use expected; a user exit passes null. Cleanup failure does not restore UI IDs. */
    suspend fun exit(expected: PlaybackSessionVersion? = null): SessionExitResult =
        withContext(NonCancellable + playbackDispatcher) {
            val retired = mutableListOf<PreparedPlayback>()
            try {
                commands.withLock {
                    if (expected != null && expected != version) return@withLock SessionExitResult(false, false)
                    closed = true
                    backendReady.complete(Unit)
                    snapshotWriter?.stop()
                    val requestId = attempt?.requestId
                    attempt?.job?.cancel()
                    nativePreparation?.cancel()
                    attempt = null
                    reconcileCandidate(retired)
                    val sessionId = version?.sessionId
                    runCatching { attachedBackend?.detach() }
                    // Retain resources still borrowed by a failing backend; a later exit can finish detachment.
                    candidate = null
                    runtime = null
                    committedTarget = null
                    publishState()
                    collectDetached(retired)
                    if (owned.isEmpty()) attachedBackend = null
                    suspendedProgress = null
                    var cleanupFailed = false
                    if (requestId != null) cleanupFailed = store.clearPending(requestId) is HandoffStoreResult.Failure
                    if (sessionId != null) cleanupFailed = (store.clearSnapshot(sessionId) is HandoffStoreResult.Failure) || cleanupFailed
                    SessionExitResult(owned.isEmpty(), cleanupFailed)
                }
            } finally {
                withContext(cleanupDispatcher) { retired.forEach(PreparedPlayback::release) }
            }
        }

    private suspend fun reconcileCandidate(retired: MutableList<PreparedPlayback>): Boolean {
        val queued = candidate ?: return true
        return when (runCatching { backend.reconcileCandidate(queued) }.getOrDefault(QueuedPlaybackStatus.STILL_ATTACHED)) {
            QueuedPlaybackStatus.STILL_ATTACHED -> false
            QueuedPlaybackStatus.DETACHED -> {
                if (backend.isAttached(queued.source)) return false
                candidate = null
                collectDetached(retired)
                true
            }
            QueuedPlaybackStatus.CURRENT -> {
                if (backend.currentSource() !== queued.source) return false
                val previous = checkNotNull(runtime)
                val active = ActivePlayback(nextVersion(previous.version.sessionId), previous.requestId, queued)
                runtime = active
                candidate = null
                backend.adoptNative(queued, active.version)
                publishState()
                collectDetached(retired)
                val progress = backend.progress()
                val target =
                    queued.snapshot(active.version, active.requestId).copy(
                        positionMs = progress.positionMs.coerceAtLeast(0),
                        playWhenReady = progress.playWhenReady
                    )
                when (val saved = store.saveSnapshot(target) { it == runtime?.version }) {
                    is HandoffStoreResult.Success -> {
                        committedTarget = saved.value.target
                        recoverySaveFailed = false
                    }
                    is HandoffStoreResult.Failure -> recoverySaveFailed = true
                }
                publishState()
                true
            }
        }
    }

    private fun nextVersion(firstRequestId: String): PlaybackSessionVersion =
        version?.let { it.copy(sourceRevision = it.sourceRevision + 1) } ?: PlaybackSessionVersion(firstRequestId, 1)

    private fun collectDetached(retired: MutableList<PreparedPlayback>) {
        val detached = owned.filter { it !== runtime?.playback && it !== candidate && attachedBackend?.isAttached(it.source) != true }
        owned.removeAll(detached.toSet())
        retired.addAll(detached)
    }

    private suspend fun reject(
        requestId: String,
        failure: PlaybackPreparationFailure
    ): SessionRequestResult.Rejected = SessionRequestResult.Rejected(failure, store.clearPending(requestId) is HandoffStoreResult.Failure)

    private suspend fun reject(
        requestId: String,
        result: HandoffStoreResult.Failure
    ): SessionRequestResult.Rejected {
        val failure =
            when (result.failure.kind) {
                HandoffStoreFailureKind.REQUEST_MISSING -> PlaybackPreparationFailure.RequestMissing
                HandoffStoreFailureKind.REQUEST_EXPIRED -> PlaybackPreparationFailure.RequestExpired
                HandoffStoreFailureKind.REQUEST_SUPERSEDED, HandoffStoreFailureKind.STALE_SESSION -> PlaybackPreparationFailure.RequestSuperseded
                HandoffStoreFailureKind.SENSITIVE_PAYLOAD_UNAVAILABLE -> PlaybackPreparationFailure.SensitivePayloadUnavailable
                else -> PlaybackPreparationFailure.RecoveryStatePersistenceFailed
            }
        val rejected = reject(requestId, failure)
        return rejected.copy(cleanupFailed = rejected.cleanupFailed || result.failure.cleanupFailed)
    }

    private class Preparation(
        val requestId: String?,
        val expected: PlaybackSessionVersion?,
        val job: Job
    )
}
