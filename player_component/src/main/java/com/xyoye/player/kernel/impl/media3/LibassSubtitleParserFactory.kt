package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/** ASS pixels belong to libass; all other formats retain Media3's complete Cue representation. */
@UnstableApi
internal class LibassSubtitleParserFactory(
    private val input: Media3SubtitleInput
) : SubtitleParser.Factory {
    private val delegate = DefaultSubtitleParserFactory()

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        if (!Media3SubtitleFormatClassifier.isSsaMime(format.sampleMimeType)) return delegate.create(format)
        // Matroska events are delivered by the public TrackOutput adapter with container timing.
        val track =
            if (format.containerMimeType == MimeTypes.VIDEO_MATROSKA) {
                null
            } else {
                input.createTrack(requireNotNull(format.id)).also { it.onFormat(null) }
            }
        return object : SubtitleParser {
            override fun parse(
                data: ByteArray,
                offset: Int,
                length: Int,
                outputOptions: SubtitleParser.OutputOptions,
                output: Consumer<CuesWithTiming>
            ) {
                track?.onDocument(data.copyOfRange(offset, offset + length))
            }

            override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
        }
    }
}
