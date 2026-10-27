package com.xyoye.common_component.playback

import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.playback.handoff.PlaybackPreparationFailure
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.services.PlaybackRequestService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** One-shot navigation from the latest entry action. This owns no player or resolved resource. */
class PlaybackEntryRequests(
    private val scope: CoroutineScope,
    private val onLoading: (Boolean) -> Unit = {},
    private val submitRequest: suspend (PlaybackRequestInput) -> PlaybackSubmissionResult = { input ->
        ARouter.getInstance().navigation(PlaybackRequestService::class.java)?.submit(input)
            ?: PlaybackSubmissionResult.Rejected(PlaybackPreparationFailure.UnsupportedSource)
    }
) {
    private val results = Channel<PlaybackSubmissionResult>(Channel.CONFLATED)
    val outcomes = results.receiveAsFlow()
    private var generation = 0L
    private var pending: Job? = null

    /** Call from the UI thread; capture mutable browser references before constructing this block. */
    fun submit(input: suspend () -> PlaybackRequestInput): Job {
        val expected = ++generation
        pending?.cancel()
        results.tryReceive()
        onLoading(true)
        return scope
            .launch {
                try {
                    val prepared = input()
                    currentCoroutineContext().ensureActive()
                    val result = submitRequest(prepared)
                    currentCoroutineContext().ensureActive()
                    if (generation == expected) results.trySend(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (generation == expected) {
                        results.trySend(PlaybackSubmissionResult.Rejected(PlaybackPreparationFailure.Unknown(failure.javaClass.simpleName)))
                    }
                } finally {
                    if (generation == expected) onLoading(false)
                }
            }.also { pending = it }
    }
}
