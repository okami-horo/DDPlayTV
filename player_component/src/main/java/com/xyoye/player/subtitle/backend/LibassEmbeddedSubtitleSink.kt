package com.xyoye.player.subtitle.backend

import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.subtitle.gpu.AssGpuRenderer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Default EmbeddedSubtitleSink that streams SSA/ASS samples into the GPU libass renderer.
 */
@UnstableApi
class LibassEmbeddedSubtitleSink(
    private val renderer: AssGpuRenderer,
    private val fontDirectories: List<String>,
    private val defaultFont: String?,
    private val onContentChanged: () -> Unit = {}
) : EmbeddedSubtitleSink {
    private val lock = Any()
    private var initialized = false
    private val sampleLogCounter = AtomicInteger(0)

    override fun onFormat(codecPrivate: ByteArray?) {
        synchronized(lock) {
            renderer.initEmbeddedTrack(codecPrivate, fontDirectories, defaultFont)
            initialized = true
            if (PlayerInitializer.isPrintLog) {
                LogFacade.d(
                    LogModule.PLAYER,
                    LOG_TAG,
                    "embedded sink onFormat codecPrivateSize=${codecPrivate?.size ?: -1} fonts=${fontDirectories.size} defaultFontSet=${defaultFont != null}",
                )
            }
        }
    }

    override fun onSample(
        data: ByteArray,
        timeUs: Long,
        durationUs: Long?
    ) {
        synchronized(lock) {
            if (!initialized) {
                renderer.initEmbeddedTrack(null, fontDirectories, defaultFont)
                initialized = true
            }
            val timeMs = TimeUnit.MICROSECONDS.toMillis(timeUs)
            val durationMs = durationUs?.let { TimeUnit.MICROSECONDS.toMillis(it) }
            renderer.appendEmbeddedSample(data, timeMs, durationMs)
            onContentChanged()
            if (PlayerInitializer.isPrintLog && shouldLog(sampleLogCounter.incrementAndGet())) {
                LogFacade.d(
                    LogModule.PLAYER,
                    LOG_TAG,
                    "embedded sink onSample size=${data.size} ptsUs=$timeUs durationUs=${durationUs ?: -1} ptsMs=$timeMs durationMs=${durationMs ?: -1}",
                )
            }
        }
    }

    override fun onFlush() {
        synchronized(lock) {
            renderer.flushEmbeddedEvents()
            onContentChanged()
            if (PlayerInitializer.isPrintLog) {
                LogFacade.d(LogModule.PLAYER, LOG_TAG, "embedded sink onFlush")
            }
        }
    }

    override fun onRelease() {
        synchronized(lock) {
            renderer.clearEmbeddedTrack()
            initialized = false
            if (PlayerInitializer.isPrintLog) {
                LogFacade.d(LogModule.PLAYER, LOG_TAG, "embedded sink onRelease")
            }
        }
    }

    private fun shouldLog(count: Int): Boolean = count <= LOG_SAMPLE_LIMIT || count % LOG_SAMPLE_INTERVAL == 0

    private companion object {
        private const val LOG_TAG = "PlayerSubtitle"
        private const val LOG_SAMPLE_LIMIT = 6
        private const val LOG_SAMPLE_INTERVAL = 50
    }
}
