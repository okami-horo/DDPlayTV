package com.xyoye.player.subtitle.gpu

/** One indivisible publication; target time never changes content sampling time. */
internal data class SubtitleFrameRequest(
    val contentTimeUs: Long,
    val targetPresentTimeNs: Long?,
    val stateRevision: Long,
    val surfaceRevision: Long,
    val requestedAtNs: Long = System.nanoTime()
)
