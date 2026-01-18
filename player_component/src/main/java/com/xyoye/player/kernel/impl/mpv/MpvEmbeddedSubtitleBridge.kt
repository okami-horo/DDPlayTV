package com.xyoye.player.kernel.impl.mpv

import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.util.UnstableApi
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import java.util.concurrent.TimeUnit

@UnstableApi
internal class MpvEmbeddedSubtitleBridge(
    private val parser: MpvAssFullSubtitleParser = MpvAssFullSubtitleParser()
) {
    private val lock = Any()

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var sink: EmbeddedSubtitleSink? = null

    private var lastSid: String? = null
    private var lastAssExtradata: String? = null
    private var lastAssFull: String? = null

    fun setSink(value: EmbeddedSubtitleSink?) {
        synchronized(lock) {
            sink = value
        }
        post {
            parser.reset()
            val activeSink = activeSink()
            if (activeSink == null) {
                lastSid = null
                lastAssExtradata = null
                lastAssFull = null
                return@post
            }
            lastAssExtradata?.let { extradata ->
                activeSink.onFormat(extradata.toByteArray(Charsets.UTF_8))
            }
            lastAssFull?.let { assFull ->
                deliverAssFull(activeSink, assFull)
            }
        }
    }

    fun onAssFull(value: String?) {
        if (value.isNullOrBlank()) return
        post {
            lastAssFull = value
            val activeSink = activeSink() ?: return@post
            deliverAssFull(activeSink, value)
        }
    }

    fun onAssExtradata(value: String?) {
        post {
            lastAssExtradata = value
            val activeSink = activeSink() ?: return@post
            activeSink.onFormat(value?.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8))
        }
    }

    fun onSid(value: String?) {
        post {
            if (lastSid == value) return@post
            lastSid = value
            lastAssExtradata = null
            lastAssFull = null
            resetForTimelineChangeInternal()
        }
    }

    fun resetForTimelineChange() {
        post {
            resetForTimelineChangeInternal()
        }
    }

    fun release() {
        val (handler, thread) =
            synchronized(lock) {
                sink = null
                lastSid = null
                lastAssExtradata = null
                lastAssFull = null
                val handler = this.handler
                val thread = handlerThread
                this.handler = null
                handlerThread = null
                handler to thread
            }
        handler?.removeCallbacksAndMessages(null)
        if (thread != null) runCatching { thread.quitSafely() }
        if (thread != null) runCatching { thread.join(1500) }
        parser.reset()
    }

    private fun deliverAssFull(
        sink: EmbeddedSubtitleSink,
        assFull: String
    ) {
        val samples = parser.parse(assFull)
        samples.forEach { sample ->
            val timeUs = TimeUnit.MILLISECONDS.toMicros(sample.timecodeMs)
            val durationUs = TimeUnit.MILLISECONDS.toMicros(sample.durationMs)
            sink.onSample(sample.data, timeUs, durationUs)
        }
    }

    private fun activeSink(): EmbeddedSubtitleSink? =
        synchronized(lock) {
            sink
        }

    private fun resetForTimelineChangeInternal() {
        parser.reset()
        activeSink()?.onFlush()
    }

    private fun post(action: () -> Unit) {
        val handler = ensureHandler() ?: return
        handler.post(action)
    }

    private fun ensureHandler(): Handler? {
        synchronized(lock) {
            handler?.let { return it }
            val thread = HandlerThread("MpvEmbeddedSubtitleBridge").apply { start() }
            val newHandler = Handler(thread.looper)
            handlerThread = thread
            handler = newHandler
            return newHandler
        }
    }
}
