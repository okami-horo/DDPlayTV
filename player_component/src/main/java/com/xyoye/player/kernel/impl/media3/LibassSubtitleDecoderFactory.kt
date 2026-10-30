package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.extractor.text.SubtitleDecoder
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
class LibassSubtitleDecoderFactory(
    private val sinkProvider: () -> EmbeddedSubtitleSink?
) : SubtitleDecoderFactory {
    private val defaultFactory = SubtitleDecoderFactory.DEFAULT
    private val supportsLogCounter = AtomicInteger(0)
    private val createLogCounter = AtomicInteger(0)
    private var sourceInput: Media3SubtitleInput? = null

    internal fun useSource(input: Media3SubtitleInput?) {
        sourceInput = input
    }

    internal fun hasSink(): Boolean = sourceInput != null || sinkProvider() != null

    private fun currentSink(): EmbeddedSubtitleSink? = sourceInput?.createDecoderSink(allowDetached = true) ?: sinkProvider()

    override fun supportsFormat(format: Format): Boolean {
        val sinkPresent = hasSink()
        val ssaMime = Media3SubtitleFormatClassifier.isSsaMime(format.sampleMimeType)
        val supportsByLibass = sinkPresent && ssaMime
        if (shouldLog(supportsLogCounter.incrementAndGet())) {
            logDecision(
                prefix = "supportsFormat",
                format = format,
                sinkPresent = sinkPresent,
                isSsaMime = ssaMime,
                useLibassPath = supportsByLibass,
            )
        }
        if (supportsByLibass) {
            return true
        }
        return defaultFactory.supportsFormat(format)
    }

    override fun createDecoder(format: Format): SubtitleDecoder =
        if (hasSink() && Media3SubtitleFormatClassifier.isSsaMime(format.sampleMimeType)) {
            if (shouldLog(createLogCounter.incrementAndGet())) {
                logDecision(
                    prefix = "createDecoder",
                    format = format,
                    sinkPresent = true,
                    isSsaMime = true,
                    useLibassPath = true,
                )
            }
            val sink = currentSink()
            LibassSsaStreamDecoder(format) { sink }
        } else {
            if (shouldLog(createLogCounter.incrementAndGet())) {
                val sinkPresent = hasSink()
                val ssaMime = Media3SubtitleFormatClassifier.isSsaMime(format.sampleMimeType)
                logDecision(
                    prefix = "createDecoder",
                    format = format,
                    sinkPresent = sinkPresent,
                    isSsaMime = ssaMime,
                    useLibassPath = false,
                )
            }
            defaultFactory.createDecoder(format)
        }

    private fun logDecision(
        prefix: String,
        format: Format,
        sinkPresent: Boolean,
        isSsaMime: Boolean,
        useLibassPath: Boolean
    ) {
        if (!PlayerInitializer.isPrintLog) {
            return
        }
        LogFacade.d(
            LogModule.PLAYER,
            "PlayerSubtitle",
            "libass decoderFactory $prefix sinkPresent=$sinkPresent mime=${format.sampleMimeType} isSsaMime=$isSsaMime useLibassPath=$useLibassPath codecs=${format.codecs}",
        )
    }

    private fun shouldLog(count: Int): Boolean = count <= LOG_SAMPLE_LIMIT || count % LOG_SAMPLE_INTERVAL == 0

    private companion object {
        private const val LOG_SAMPLE_LIMIT = 6
        private const val LOG_SAMPLE_INTERVAL = 50
    }
}
