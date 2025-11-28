package com.xyoye.player.subtitle.backend

import androidx.media3.common.util.UnstableApi
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
object SubtitleRendererRegistry {
    private val rendererRef = AtomicReference<SubtitleRenderer?>(null)

    fun register(renderer: SubtitleRenderer) {
        rendererRef.set(renderer)
    }

    fun unregister(renderer: SubtitleRenderer?) {
        rendererRef.compareAndSet(renderer, null)
    }

    fun current(): SubtitleRenderer? = rendererRef.get()
}
