package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.extractor.text.SubtitleDecoder
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink

@UnstableApi
class LibassSubtitleDecoderFactory(
    private val sinkProvider: () -> EmbeddedSubtitleSink?
) : SubtitleDecoderFactory {

    private val defaultFactory = SubtitleDecoderFactory.DEFAULT

    override fun supportsFormat(format: Format): Boolean {
        return isSsaMime(format.sampleMimeType) || defaultFactory.supportsFormat(format)
    }

    override fun createDecoder(format: Format): SubtitleDecoder {
        return if (isSsaMime(format.sampleMimeType)) {
            LibassSsaStreamDecoder(format, sinkProvider)
        } else {
            defaultFactory.createDecoder(format)
        }
    }

    private fun isSsaMime(mimeType: String?): Boolean {
        if (mimeType == null) {
            return false
        }
        val normalized = mimeType.lowercase()
        return mimeType == MimeTypes.TEXT_SSA ||
            normalized == "text/ssa" ||
            normalized == "text/x-ass" ||
            normalized == "application/x-ass" ||
            normalized == "application/x-ssa" ||
            normalized == "application/ass"
    }
}
