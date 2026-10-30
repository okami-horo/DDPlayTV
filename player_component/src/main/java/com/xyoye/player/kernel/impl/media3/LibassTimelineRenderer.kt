package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.extractor.metadata.id3.BinaryFrame
import java.nio.ByteBuffer

/** Supplies an explicit renderer-to-window offset with each ASS input format. */
@UnstableApi
internal class LibassTimelineRenderer(
    val delegateRenderer: Renderer,
    private val decoderFactory: LibassSubtitleDecoderFactory? = null
) : ForwardingRenderer(delegateRenderer) {
    private var timeline: Timeline = Timeline.EMPTY
    private var sourceStream: SampleStream? = null

    override fun setTimeline(timeline: Timeline) {
        this.timeline = timeline
        super.setTimeline(timeline)
    }

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        sourceStream = stream
        selectSource(mediaPeriodId)
        super.enable(
            configuration,
            mapFormats(formats, mediaPeriodId),
            mapStream(stream, mediaPeriodId),
            positionUs,
            joining,
            mayRenderStartOfStream,
            startPositionUs,
            offsetUs,
            mediaPeriodId
        )
    }

    override fun replaceStream(
        formats: Array<Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        sourceStream = stream
        selectSource(mediaPeriodId)
        super.replaceStream(mapFormats(formats, mediaPeriodId), mapStream(stream, mediaPeriodId), startPositionUs, offsetUs, mediaPeriodId)
    }

    // ExoPlayer compares this identity with the MediaPeriod's original stream.
    override fun getStream(): SampleStream? = sourceStream

    override fun disable() {
        super.disable()
        sourceStream = null
        decoderFactory?.useSource(null)
    }

    private fun selectSource(periodId: MediaPeriodId) {
        val index = timeline.getIndexOfPeriod(periodId.periodUid)
        val input =
            if (index == C.INDEX_UNSET) {
                null
            } else {
                val period = timeline.getPeriod(index, Timeline.Period())
                timeline
                    .getWindow(period.windowIndex, Timeline.Window())
                    .mediaItem.localConfiguration
                    ?.tag as? Media3SubtitleInput
            }
        decoderFactory?.useSource(input)
    }

    private fun periodPositionUs(periodId: MediaPeriodId): Long {
        val index = timeline.getIndexOfPeriod(periodId.periodUid)
        return if (index == C.INDEX_UNSET) 0L else timeline.getPeriod(index, Timeline.Period()).positionInWindowUs
    }

    private fun mapFormats(
        formats: Array<Format>,
        periodId: MediaPeriodId
    ): Array<Format> =
        if (trackType == C.TRACK_TYPE_VIDEO) formats.map { videoFormat(it, periodPositionUs(periodId)) }.toTypedArray() else formats

    private fun videoFormat(
        format: Format,
        periodPositionUs: Long
    ): Format {
        val timing = BinaryFrame(TIMING_ID, ByteBuffer.allocate(8).putLong(periodPositionUs).array())
        val metadata = format.metadata?.copyWithAppendedEntries(timing) ?: Metadata(timing)
        return format.buildUpon().setMetadata(metadata).build()
    }

    private fun mapStream(
        stream: SampleStream,
        periodId: MediaPeriodId
    ): SampleStream =
        object : SampleStream by stream {
            override fun readData(
                holder: FormatHolder,
                buffer: DecoderInputBuffer,
                readFlags: Int
            ): Int {
                val result = stream.readData(holder, buffer, readFlags)
                if (result == C.RESULT_FORMAT_READ) {
                    val format = holder.format
                    val periodPositionUs = periodPositionUs(periodId)
                    if (format != null && trackType == C.TRACK_TYPE_VIDEO) {
                        holder.format = videoFormat(format, periodPositionUs)
                    } else if (decoderFactory?.hasSink() == true &&
                        format != null &&
                        Media3SubtitleFormatClassifier.isSsaMime(format.sampleMimeType)
                    ) {
                        // BaseRenderer adds its real streamOffsetUs to this value, and to sample.timeUs.
                        // Their difference is therefore sample period time + period position in window.
                        holder.format = format.buildUpon().setSubsampleOffsetUs(-periodPositionUs).build()
                    }
                }
                return result
            }
        }

    companion object {
        private const val TIMING_ID = "DDPT"

        fun videoPeriodPositionUs(format: Format): Long? {
            val metadata = format.metadata ?: return null
            for (index in 0 until metadata.length()) {
                val entry = metadata[index]
                if (entry is BinaryFrame && entry.id == TIMING_ID && entry.data.size == 8) {
                    return ByteBuffer.wrap(entry.data).long
                }
            }
            return null
        }
    }
}
