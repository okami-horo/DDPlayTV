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
    OUTPUT_BUFFER_POOL
), SubtitleDecoder {

    private val decoderName = "LibassSsaStreamDecoder"

    init {
        setInitialInputBufferSize(INITIAL_INPUT_BUFFER_SIZE)
        sinkProvider()?.onFormat(format.initializationData.firstOrNull())
    }

    override fun getName(): String = decoderName

    override fun setPositionUs(positionUs: Long) {
        // No-op
    }

    override fun createInputBuffer(): SubtitleInputBuffer = SubtitleInputBuffer()

    override fun createOutputBuffer(): SubtitleOutputBuffer = createDecoderOutputBuffer()

    override fun createUnexpectedDecodeException(error: Throwable): SubtitleDecoderException {
        return SubtitleDecoderException("Unexpected decode error", error)
    }

    override fun decode(
        inputBuffer: SubtitleInputBuffer,
        outputBuffer: SubtitleOutputBuffer,
        reset: Boolean
    ): SubtitleDecoderException? {
        if (reset) {
            sinkProvider()?.onFlush()
        }
        val buffer = inputBuffer.data ?: return null
        val length = buffer.limit()
        val payload = copyPayload(buffer, length)
        if (payload.isNotEmpty()) {
            sinkProvider()?.onSample(payload, inputBuffer.timeUs, null)
        }
        outputBuffer.setContent(inputBuffer.timeUs, EmptySubtitle, inputBuffer.subsampleOffsetUs)
        return null
    }

    private fun copyPayload(buffer: ByteBuffer, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val payload = ByteArray(length)
        buffer.position(0)
        buffer.get(payload, 0, length)
        buffer.position(0)
        return payload
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

        private val INPUT_BUFFER_POOL = Array(BUFFER_POOL_SIZE) { SubtitleInputBuffer() }
        private val OUTPUT_BUFFER_POOL = Array(BUFFER_POOL_SIZE) {
            // Placeholder entries; they are replaced in SimpleDecoder's constructor via createOutputBuffer().
            object : SubtitleOutputBuffer() {
                override fun release() {
                    clear()
                }
            }
        }
    }

    private fun createDecoderOutputBuffer(): SubtitleOutputBuffer {
        return object : SubtitleOutputBuffer() {
            override fun release() {
                this@LibassSsaStreamDecoder.releaseOutputBuffer(this)
            }
        }
    }
}
