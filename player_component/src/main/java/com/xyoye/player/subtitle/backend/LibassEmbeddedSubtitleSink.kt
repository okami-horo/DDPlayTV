package com.xyoye.player.subtitle.backend

import androidx.media3.common.util.UnstableApi
import com.xyoye.player.subtitle.gpu.AssGpuRenderer
import java.util.concurrent.TimeUnit

/**
 * Default EmbeddedSubtitleSink that streams SSA/ASS samples into the GPU libass renderer.
 */
@UnstableApi
class LibassEmbeddedSubtitleSink(
    private val renderer: AssGpuRenderer,
    private val fontDirectories: List<String>,
    private val defaultFont: String?
) : EmbeddedSubtitleSink {
    private val lock = Any()
    private var released = false
    private var initialized = false

    override fun onFormat(codecPrivate: ByteArray?) {
        synchronized(lock) {
            if (released) return
            renderer.initEmbeddedTrack(codecPrivate, fontDirectories, defaultFont)
            initialized = true
        }
    }

    override fun onSample(
        data: ByteArray,
        timeUs: Long,
        durationUs: Long?
    ) {
        synchronized(lock) {
            if (released) return
            if (!initialized) {
                renderer.initEmbeddedTrack(null, fontDirectories, defaultFont)
                initialized = true
            }
            val timeMs = TimeUnit.MICROSECONDS.toMillis(timeUs)
            val durationMs = durationUs?.let { TimeUnit.MICROSECONDS.toMillis(it) }
            renderer.appendEmbeddedSample(data, timeMs, durationMs)
        }
    }

    override fun onFlush() {
        synchronized(lock) {
            if (released) return
            renderer.flushEmbeddedEvents()
        }
    }

    override fun onRelease() {
        synchronized(lock) {
            if (released) return
            renderer.clearEmbeddedTrack()
            released = true
        }
    }
}
