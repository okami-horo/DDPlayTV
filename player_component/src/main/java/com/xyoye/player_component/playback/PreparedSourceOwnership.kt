package com.xyoye.player_component.playback

import com.xyoye.common_component.source.base.BaseVideoSource

/** Own the candidate across dispatch to the backend's thread, including prompt cancellation. */
internal suspend fun withPreparedPlaybackSource(
    prepare: suspend () -> BaseVideoSource?,
    apply: suspend (BaseVideoSource?) -> Unit,
    isAttached: (BaseVideoSource) -> Boolean,
    retire: (BaseVideoSource) -> Unit
) {
    var candidate: BaseVideoSource? = null
    try {
        candidate = prepare()
        apply(candidate)
    } finally {
        candidate?.takeUnless(isAttached)?.let(retire)
    }
}
