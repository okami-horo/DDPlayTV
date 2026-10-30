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
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * SubtitleDecoder that forwards SSA/ASS samples directly to the libass GPU backend and
 * suppresses cue output to the legacy text pipeline.
 */
@UnstableApi
class LibassSsaStreamDecoder(
    private val format: Format,
    private val sinkProvider: () -> EmbeddedSubtitleSink?
) : SimpleDecoder<SubtitleInputBuffer, SubtitleOutputBuffer, SubtitleDecoderException>(
        Array(BUFFER_POOL_SIZE) { SubtitleInputBuffer() },
        Array<SubtitleOutputBuffer>(BUFFER_POOL_SIZE) {
            object : SubtitleOutputBuffer() {
                override fun release() = clear()
            }
        },
    ),
    SubtitleDecoder {
    private val decoderName = "LibassSsaStreamDecoder"
    private val decodeLogCounter = AtomicInteger(0)
    private val sinkMissLogCounter = AtomicInteger(0)
    private var activeSink: EmbeddedSubtitleSink? = null

    init {
        setInitialInputBufferSize(INITIAL_INPUT_BUFFER_SIZE)
        val sink = sinkProvider()
        activeSink = sink
        val codecPrivate = buildCodecPrivateForLibass(format.initializationData)
        sink?.onFormat(codecPrivate)
        if (PlayerInitializer.isPrintLog) {
            val initSizes = format.initializationData.joinToString(prefix = "[", postfix = "]") { it.size.toString() }
            LogFacade.d(
                LogModule.PLAYER,
                LOG_TAG,
                "decoder init sinkPresent=${sink != null} mime=${format.sampleMimeType} codecs=${format.codecs} initDataCount=${format.initializationData.size} initSizes=$initSizes codecPrivateSize=${codecPrivate?.size ?: -1}",
            )
        }
    }

    override fun getName(): String = decoderName

    override fun release() {
        super.release()
        activeSink?.onRelease()
        activeSink = null
    }

    override fun setPositionUs(positionUs: Long) {
        // Timing is carried by each input format, including after seek/period changes.
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
        val sink = sinkProvider()
        if (sink !== activeSink) {
            activeSink?.onRelease()
            activeSink = sink
            sink?.onFormat(buildCodecPrivateForLibass(format.initializationData))
        }
        if (reset) {
            sink?.onFlush()
            if (PlayerInitializer.isPrintLog && shouldLog(decodeLogCounter.incrementAndGet())) {
                LogFacade.d(LogModule.PLAYER, LOG_TAG, "decode reset=true")
            }
        }
        val buffer = inputBuffer.data ?: return null
        val payload = copyPayload(buffer)
        if (payload.isNotEmpty()) {
            val normalized = normalizeMedia3MatroskaSample(payload)
            val sinkTimeUs = normalizeSampleTimeUs(inputBuffer.timeUs, inputBuffer.subsampleOffsetUs)
            sink?.onSample(normalized.data, sinkTimeUs, normalized.durationUs)
            if (PlayerInitializer.isPrintLog && shouldLog(decodeLogCounter.incrementAndGet())) {
                LogFacade.d(
                    LogModule.PLAYER,
                    LOG_TAG,
                    "decode sample payloadSize=${payload.size} normalizedSize=${normalized.data.size} sampleTimeUs=${inputBuffer.timeUs} subsampleOffsetUs=${inputBuffer.subsampleOffsetUs} sinkTimeUs=$sinkTimeUs durationUs=${normalized.durationUs ?: -1} sinkPresent=${sink != null}",
                )
            } else if (sink == null && PlayerInitializer.isPrintLog && shouldLog(sinkMissLogCounter.incrementAndGet())) {
                LogFacade.w(
                    LogModule.PLAYER,
                    LOG_TAG,
                    "decode sample dropped due to missing sink payloadSize=${payload.size} ptsUs=${inputBuffer.timeUs}",
                )
            }
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
        val durationSeparatorIndex = payload.indexOfByte(SSA_FIELD_SEPARATOR, SSA_PREFIX.size)
        if (durationSeparatorIndex <= SSA_PREFIX.size) {
            return NormalizedSample(payload, null)
        }
        val durationUs = parseSsaDurationUs(payload, SSA_PREFIX.size, durationSeparatorIndex)
        val dataStart = durationSeparatorIndex + 1
        val data =
            if (dataStart >= payload.size) {
                ByteArray(0)
            } else {
                payload.copyOfRange(dataStart, payload.size)
            }
        return NormalizedSample(data, durationUs)
    }

    internal fun normalizeSampleForTest(payload: ByteArray): Pair<ByteArray, Long?> {
        val normalized = normalizeMedia3MatroskaSample(payload)
        return normalized.data to normalized.durationUs
    }

    private fun parseSsaDurationUs(
        payload: ByteArray,
        offset: Int,
        endExclusive: Int
    ): Long? {
        if (endExclusive - offset != SSA_TIMECODE_LENGTH) return null
        if (payload.size < endExclusive) return null
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
        if (second.hasEventsSectionFormat()) {
            return second
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

    private fun ByteArray.indexOfByte(
        value: Byte,
        startIndex: Int
    ): Int {
        if (startIndex >= size) return -1
        for (index in startIndex until size) {
            if (this[index] == value) return index
        }
        return -1
    }

    /**
     * Ensures the initialization block is structurally complete for ASS events:
     * [Events] section exists and contains its own `Format:` line.
     */
    private fun ByteArray.hasEventsSectionFormat(): Boolean {
        if (isEmpty()) return false
        var inEventsSection = false
        val content = String(this, Charsets.UTF_8)
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim().trimStart('\uFEFF')
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                inEventsSection = line.equals(EVENTS_SECTION_NAME, ignoreCase = true)
                continue
            }
            if (inEventsSection && line.startsWith(FORMAT_PREFIX_TEXT, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    internal fun normalizeSampleTimeUs(
        sampleTimeUs: Long,
        subsampleOffsetUs: Long
    ): Long {
        // LibassTimelineRenderer supplies the actual stream offset minus period position.
        // A decoder used without that adapter must never infer an offset from timestamp magnitude.
        val effectiveOffsetUs =
            when (subsampleOffsetUs) {
                Format.OFFSET_SAMPLE_RELATIVE -> 0L
                C.TIME_UNSET -> 0L
                else -> subsampleOffsetUs
            }
        // Negative starts are meaningful for events overlapping a clipped window's beginning.
        return sampleTimeUs - effectiveOffsetUs
    }

    private object EmptySubtitle : Subtitle {
        override fun getNextEventTimeIndex(timeUs: Long): Int = C.INDEX_UNSET

        override fun getEventTimeCount(): Int = 0

        override fun getEventTime(index: Int): Long = C.TIME_UNSET

        override fun getCues(timeUs: Long): List<Cue> = emptyList()
    }

    companion object {
        private const val LOG_TAG = "PlayerSubtitle"
        private const val LOG_SAMPLE_LIMIT = 6
        private const val LOG_SAMPLE_INTERVAL = 50

        private const val INITIAL_INPUT_BUFFER_SIZE = 1024
        private const val BUFFER_POOL_SIZE = 2

        private const val SSA_TIMECODE_LENGTH = 10
        private const val SSA_TIMECODE_LAST_VALUE_SCALING_FACTOR = 10_000L

        private val SSA_PREFIX =
            "Dialogue: 0:00:00:00,".toByteArray(Charsets.UTF_8)
        private const val SSA_FIELD_SEPARATOR: Byte = ','.code.toByte()
        private const val EVENTS_SECTION_NAME = "[Events]"
        private const val FORMAT_PREFIX_TEXT = "Format:"
        private val FORMAT_PREFIX = FORMAT_PREFIX_TEXT.toByteArray(Charsets.UTF_8)
        private val EVENTS_HEADER = "\n[Events]\n".toByteArray(Charsets.UTF_8)
        private val NEWLINE = "\n".toByteArray(Charsets.UTF_8)
    }

    private fun createDecoderOutputBuffer(): SubtitleOutputBuffer =
        object : SubtitleOutputBuffer() {
            override fun release() {
                this@LibassSsaStreamDecoder.releaseOutputBuffer(this)
            }
        }

    private fun shouldLog(count: Int): Boolean = count <= LOG_SAMPLE_LIMIT || count % LOG_SAMPLE_INTERVAL == 0
}
