package com.xyoye.player.subtitle.backend

import androidx.media3.common.util.UnstableApi

/**
 * Thread-safe bridge for delivering embedded subtitle samples to the GPU libass renderer.
 */
@UnstableApi
interface EmbeddedSubtitleSink {
    fun onFormat(codecPrivate: ByteArray?)

    fun onSample(
        data: ByteArray,
        timeUs: Long,
        durationUs: Long?
    )

    fun onFlush()

    fun onRelease()
}
