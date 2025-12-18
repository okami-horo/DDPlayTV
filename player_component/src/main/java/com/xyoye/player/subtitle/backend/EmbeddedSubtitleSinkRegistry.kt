package com.xyoye.player.subtitle.backend

import androidx.media3.common.util.UnstableApi
import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton holder for the current embedded subtitle sink.
 */
@UnstableApi
object EmbeddedSubtitleSinkRegistry {
    private val sinkRef = AtomicReference<EmbeddedSubtitleSink?>()

    fun register(sink: EmbeddedSubtitleSink) {
        sinkRef.set(sink)
    }

    fun unregister(sink: EmbeddedSubtitleSink?) {
        sinkRef.compareAndSet(sink, null)
    }

    fun current(): EmbeddedSubtitleSink? = sinkRef.get()
}
