package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
internal object Media3SubtitleFormatClassifier {
    fun isMedia3CueFormat(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES

    fun isSsaFamilyTrack(format: Format): Boolean =
        isSsaMime(format.sampleMimeType) ||
            (isMedia3CueFormat(format) && isSsaCodecs(format.codecs))

    fun isSsaCodecs(codecs: String?): Boolean {
        if (codecs.isNullOrBlank()) {
            return false
        }
        return codecs
            .split(',')
            .asSequence()
            .mapNotNull { normalizeToken(it) }
            .any { codec -> codec in SSA_CODEC_ALIASES }
    }

    fun isSsaMime(mimeType: String?): Boolean {
        val normalized = normalizeToken(mimeType)
        if (normalized == null) {
            return false
        }
        return normalized in SSA_MIME_ALIASES
    }

    private fun normalizeToken(value: String?): String? =
        value
            ?.trim()
            ?.substringBefore(';')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }

    private val SSA_MIME_ALIASES =
        setOf(
            MimeTypes.TEXT_SSA.lowercase(Locale.ROOT),
            "text/ssa",
            "text/x-ass",
            "application/x-ass",
            "application/x-ssa",
            "application/ass",
        )

    private val SSA_CODEC_ALIASES =
        SSA_MIME_ALIASES + setOf("ssa", "ass")
}
