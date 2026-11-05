package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.UUID

@UnstableApi
internal class LibassSubtitleExtractorOutput(
    private val delegate: ExtractorOutput,
    private val input: Media3SubtitleInput
) : ExtractorOutput by delegate {
    private val tracks = mutableMapOf<Int, AssTrackOutput>()

    override fun track(
        id: Int,
        type: Int
    ): TrackOutput =
        if (type == C.TRACK_TYPE_TEXT) tracks.getOrPut(id) { AssTrackOutput(delegate.track(id, type)) } else delegate.track(id, type)

    fun reset() = tracks.values.forEach { it.reset() }

    fun release() = tracks.values.forEach { it.release() }

    private inner class AssTrackOutput(
        private val delegate: TrackOutput
    ) : TrackOutput by delegate {
        private var format: Format? = null
        private var sink: EmbeddedSubtitleSink? = null
        private val buffer = ByteArrayOutputStream()

        override fun format(format: Format) {
            if (format == this.format) return
            this.format = format
            sink?.onRelease()
            sink = null
            buffer.reset()
            if (Media3SubtitleFormatClassifier.isSsaMime(format.sampleMimeType)) {
                val id = "dd-ass-${UUID.randomUUID()}"
                sink = input.createTrack(id).also { it.onFormat(Media3AssSample.codecPrivate(format.initializationData)) }
                delegate.format(format.buildUpon().setId(id).build())
            } else {
                delegate.format(format)
            }
        }

        override fun sampleData(
            reader: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int {
            if (sink == null) return delegate.sampleData(reader, length, allowEndOfInput, sampleDataPart)
            val bytes = ByteArray(minOf(length, 8192))
            val count = reader.read(bytes, 0, bytes.size)
            if (count == C.RESULT_END_OF_INPUT) {
                if (!allowEndOfInput) throw EOFException()
                return count
            }
            sampleData(ParsableByteArray(bytes), count, sampleDataPart)
            return count
        }

        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
            sampleDataPart: Int
        ) {
            if (sink != null) buffer.write(data.data, data.position, length)
            delegate.sampleData(data, length, sampleDataPart)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            sink?.let {
                val bytes = buffer.toByteArray()
                val start = bytes.size - offset - size
                require(start >= 0)
                val (data, durationUs) = Media3AssSample.normalize(bytes.copyOfRange(start, start + size))
                it.onSample(data, timeUs, durationUs)
                buffer.reset()
                buffer.write(bytes, bytes.size - offset, offset)
            }
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }

        fun reset() {
            buffer.reset()
            sink?.onFlush()
        }

        fun release() {
            buffer.reset()
            sink?.onRelease()
            sink = null
        }
    }
}
