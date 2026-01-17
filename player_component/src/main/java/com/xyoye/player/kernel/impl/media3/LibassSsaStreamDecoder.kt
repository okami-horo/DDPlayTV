package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleDecoderException
import androidx.media3.extractor.text.SubtitleInputBuffer
import androidx.media3.extractor.text.SubtitleOutputBuffer
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import java.nio.ByteBuffer

/**
 * SubtitleDecoder that forwards SSA/ASS samples directly to the libass GPU backend and
 * suppresses cue output to the legacy text pipeline.
 */
@UnstableApi
class LibassSsaStreamDecoder(
    private val format: Format,
    private val sinkProvider: () -> EmbeddedSubtitleSink?
) : SimpleDecoder<SubtitleInputBuffer, SubtitleOutputBuffer, SubtitleDecoderException>(
        INPUT_BUFFER_POOL,
        OUTPUT_BUFFER_POOL,
    ),
    SubtitleDecoder {
    private val decoderName = "LibassSsaStreamDecoder"

    init {
        setInitialInputBufferSize(INITIAL_INPUT_BUFFER_SIZE)
        sinkProvider()?.onFormat(buildCodecPrivateForLibass(format.initializationData))
    }

    override fun getName(): String = decoderName

    override fun setPositionUs(positionUs: Long) {
        // No-op
    }

    override fun createInputBuffer(): SubtitleInputBuffer = SubtitleInputBuffer()

    override fun createOutputBuffer(): SubtitleOutputBuffer = createDecoderOutputBuffer()

    override fun createUnexpectedDecodeException(error: Throwable): SubtitleDecoderException =
        SubtitleDecoderException("Unexpected decode error", error)

    override fun decode(
        inputBuffer: SubtitleInputBuffer,
        outputBuffer: SubtitleOutputBuffer,
        reset: Boolean
    ): SubtitleDecoderException? {
        if (reset) {
            sinkProvider()?.onFlush()
        }
        val buffer = inputBuffer.data ?: return null
        val payload = copyPayload(buffer)
        if (payload.isNotEmpty()) {
            val normalized = normalizeMedia3MatroskaSample(payload)
            sinkProvider()?.onSample(normalized.data, inputBuffer.timeUs, normalized.durationUs)
        }
        outputBuffer.setContent(inputBuffer.timeUs, EmptySubtitle, inputBuffer.subsampleOffsetUs)
        return null
    }

    private fun copyPayload(buffer: ByteBuffer): ByteArray {
        val length = buffer.remaining()
        if (length <= 0) return ByteArray(0)
        val payload = ByteArray(length)
        val startPos = buffer.position()
        buffer.get(payload)
        buffer.position(startPos)
        return payload
    }

    private data class NormalizedSample(
        val data: ByteArray,
        val durationUs: Long?
    )

    /**
     * Media3 MatroskaExtractor wraps ASS/SSA blocks as:
     * `Dialogue: 0:00:00:00,<duration>,<raw-event-fields...>`
     *
     * libass streaming API expects raw fields (ReadOrder, Layer, Style...) with the
     * container timestamp/duration passed separately, so strip the prefix and recover duration.
     */
    private fun normalizeMedia3MatroskaSample(payload: ByteArray): NormalizedSample {
        if (!payload.startsWith(SSA_PREFIX)) {
            return NormalizedSample(payload, null)
        }
        val durationUs = parseSsaDurationUs(payload, SSA_PREFIX_END_TIMECODE_OFFSET)
        val data = payload.copyOfRange(SSA_PREFIX.size, payload.size)
        return NormalizedSample(data, durationUs)
    }

    private fun parseSsaDurationUs(
        payload: ByteArray,
        offset: Int
    ): Long? {
        if (payload.size < offset + SSA_TIMECODE_LENGTH) return null
        if (payload[offset + 1] != ':'.code.toByte() ||
            payload[offset + 4] != ':'.code.toByte() ||
            payload[offset + 7] != ':'.code.toByte()
        ) {
            return null
        }
        val hours = digit(payload[offset]) ?: return null
        val minutes =
            digit(payload[offset + 2])?.let { tens ->
                digit(payload[offset + 3])?.let { ones -> tens * 10 + ones }
            } ?: return null
        val seconds =
            digit(payload[offset + 5])?.let { tens ->
                digit(payload[offset + 6])?.let { ones -> tens * 10 + ones }
            } ?: return null
        val centiseconds =
            digit(payload[offset + 8])?.let { tens ->
                digit(payload[offset + 9])?.let { ones -> tens * 10 + ones }
            } ?: return null
        val totalSeconds = hours * 3600L + minutes * 60L + seconds
        return totalSeconds * C.MICROS_PER_SECOND + centiseconds * SSA_TIMECODE_LAST_VALUE_SCALING_FACTOR
    }

    private fun digit(value: Byte): Int? {
        val digit = value - '0'.code.toByte()
        return if (digit in 0..9) digit.toInt() else null
    }

    private fun buildCodecPrivateForLibass(initializationData: List<ByteArray>): ByteArray? {
        if (initializationData.isEmpty()) return null
        if (initializationData.size == 1) return initializationData[0]
        val first = initializationData[0]
        val second = initializationData[1]
        if (!first.startsWith(FORMAT_PREFIX)) {
            return first
        }
        val dialogueFormat = first
        val codecPrivate = second
        val eventsHeader = EVENTS_HEADER
        val newline = NEWLINE
        val merged = ByteArray(codecPrivate.size + eventsHeader.size + dialogueFormat.size + newline.size)
        var pos = 0
        codecPrivate.copyInto(merged, pos)
        pos += codecPrivate.size
        eventsHeader.copyInto(merged, pos)
        pos += eventsHeader.size
        dialogueFormat.copyInto(merged, pos)
        pos += dialogueFormat.size
        newline.copyInto(merged, pos)
        return merged
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }

    private object EmptySubtitle : Subtitle {
        override fun getNextEventTimeIndex(timeUs: Long): Int = C.INDEX_UNSET

        override fun getEventTimeCount(): Int = 0

        override fun getEventTime(index: Int): Long = C.TIME_UNSET

        override fun getCues(timeUs: Long): List<Cue> = emptyList()
    }

    companion object {
        private const val INITIAL_INPUT_BUFFER_SIZE = 1024
        private const val BUFFER_POOL_SIZE = 2

        private const val SSA_PREFIX_END_TIMECODE_OFFSET = 21
        private const val SSA_TIMECODE_LENGTH = 10
        private const val SSA_TIMECODE_LAST_VALUE_SCALING_FACTOR = 10_000L

        private val SSA_PREFIX =
            "Dialogue: 0:00:00:00,0:00:00:00,".toByteArray(Charsets.UTF_8)
        private val FORMAT_PREFIX = "Format:".toByteArray(Charsets.UTF_8)
        private val EVENTS_HEADER = "\n[Events]\n".toByteArray(Charsets.UTF_8)
        private val NEWLINE = "\n".toByteArray(Charsets.UTF_8)

        private val INPUT_BUFFER_POOL = Array(BUFFER_POOL_SIZE) { SubtitleInputBuffer() }
        private val OUTPUT_BUFFER_POOL =
            Array(BUFFER_POOL_SIZE) {
                // Placeholder entries; they are replaced in SimpleDecoder's constructor via createOutputBuffer().
                object : SubtitleOutputBuffer() {
                    override fun release() {
                        clear()
                    }
                }
            }
    }

    private fun createDecoderOutputBuffer(): SubtitleOutputBuffer =
        object : SubtitleOutputBuffer() {
            override fun release() {
                this@LibassSsaStreamDecoder.releaseOutputBuffer(this)
            }
        }
}
