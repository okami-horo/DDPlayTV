package com.xyoye.player.subtitle.backend

import androidx.media3.common.util.UnstableApi

/**
 * Optional control interface for embedded subtitle sinks.
 *
 * This is used to temporarily mute embedded ASS/SSA events while an external
 * track is loaded directly into the GPU libass pipeline.
 */
interface EmbeddedSubtitleSinkController {
    fun setEnabled(enabled: Boolean)

    fun isEnabled(): Boolean
}

/**
 * Wraps an [EmbeddedSubtitleSink] and allows muting it without detaching the sink
 * from the kernel bridge.
 */
@UnstableApi
class SwitchableEmbeddedSubtitleSink(
    private val delegate: EmbeddedSubtitleSink
) : EmbeddedSubtitleSink,
    EmbeddedSubtitleSinkController {
    private val lock = Any()

    @Volatile
    private var enabled: Boolean = true
    private var hasCachedFormat: Boolean = false
    private var cachedCodecPrivate: ByteArray? = null

    override fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            val wasEnabled = this.enabled
            this.enabled = enabled
            if (!wasEnabled && enabled && hasCachedFormat) {
                delegate.onFormat(cachedCodecPrivate?.copyOf())
            }
        }
    }

    override fun isEnabled(): Boolean = enabled

    override fun onFormat(codecPrivate: ByteArray?) {
        val codecPrivateCopy = codecPrivate?.copyOf()
        synchronized(lock) {
            cachedCodecPrivate = codecPrivateCopy
            hasCachedFormat = true
            if (enabled) {
                delegate.onFormat(codecPrivateCopy)
            }
        }
    }

    override fun onSample(
        data: ByteArray,
        timeUs: Long,
        durationUs: Long?
    ) {
        synchronized(lock) {
            if (enabled) delegate.onSample(data, timeUs, durationUs)
        }
    }

    override fun onFlush() {
        synchronized(lock) {
            if (enabled) delegate.onFlush()
        }
    }

    override fun onRelease() {
        synchronized(lock) {
            hasCachedFormat = false
            cachedCodecPrivate = null
            // A muted embedded track must not clear the external track using the same renderer.
            if (enabled) delegate.onRelease()
        }
    }
}
