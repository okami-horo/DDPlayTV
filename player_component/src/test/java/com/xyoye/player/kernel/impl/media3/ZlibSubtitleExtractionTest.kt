package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeExtractorOutput
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ZlibSubtitleExtractionTest {
    @Test
    fun splitTrackDataWithTrailingBytes_keepsBothAssEventsAndTheirContainerTimes() {
        val input = Media3SubtitleInput()
        input.beginMedia()
        val output = FakeExtractorOutput()
        val adapter = LibassSubtitleExtractorOutput(output, input)
        val track = adapter.track(1, C.TRACK_TYPE_TEXT)
        track.format(
            Format
                .Builder()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
                .build()
        )
        val first = "Dialogue: 0:00:00:00,0:00:01:00,0,0,Default,,0,0,0,,First 简体".toByteArray()
        val second = "Dialogue: 0:00:00:00,0:00:02:00,1,0,Default,,0,0,0,,Second 繁體".toByteArray()
        val split = 7
        track.sampleData(ParsableByteArray(first.copyOfRange(0, split)), split, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleData(
            ParsableByteArray(first.copyOfRange(split, first.size) + second.copyOfRange(0, split)),
            first.size,
            TrackOutput.SAMPLE_DATA_PART_MAIN
        )
        track.sampleMetadata(3_000_000, C.BUFFER_FLAG_KEY_FRAME, first.size, split, null)
        track.sampleData(ParsableByteArray(second.copyOfRange(split, second.size)), second.size - split, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(5_000_000, C.BUFFER_FLAG_KEY_FRAME, second.size, 0, null)
        val samples = mutableListOf<Pair<Long, String>>()
        input.setSink(
            object : EmbeddedSubtitleSink {
                override fun onFormat(codecPrivate: ByteArray?) = Unit

                override fun onSample(
                    data: ByteArray,
                    timeUs: Long,
                    durationUs: Long?
                ) {
                    samples.add(timeUs to data.toString(Charsets.UTF_8))
                }

                override fun onFlush() = Unit

                override fun onRelease() = Unit
            }
        )
        input.selectTrack(
            output.trackOutputs
                .valueAt(0)
                .lastFormat!!
                .id
        )
        assertEquals(listOf(3_000_000L, 5_000_000L), samples.map { it.first })
        assertEquals(listOf("0,0,Default,,0,0,0,,First 简体", "1,0,Default,,0,0,0,,Second 繁體"), samples.map { it.second })
    }

    @Test
    fun explicitZlib_producesTheSameAssSamplesAsUncompressedMkv() {
        assertEquals(extract(null), extract(0))
    }

    @Test
    fun defaultZlib_producesTheSameAssSamplesAsUncompressedMkv() {
        assertEquals(extract(null), extract(-1))
    }

    @Test
    fun headerStripping_producesTheSameAssSamplesAsUncompressedMkv() {
        assertEquals(extract(null), extract(3))
    }

    @Test
    fun publicOutputComposition_extractsAssBeforePlaybackAndReplaysOnlyTheSelectedTrack() {
        val subtitleInput = Media3SubtitleInput()
        val fonts = subtitleInput.beginMedia()
        val extractor = LibassMatroskaExtractor(fonts, subtitleInput, LibassSubtitleParserFactory(subtitleInput))
        val output = FakeExtractorOutput()
        val input = FakeExtractorInput.Builder().setData(mkv(0)).build()
        val samples = mutableListOf<Triple<String, Long, Long?>>()
        subtitleInput.setSink(
            object : EmbeddedSubtitleSink {
                override fun onFormat(codecPrivate: ByteArray?) = Unit

                override fun onSample(
                    data: ByteArray,
                    timeUs: Long,
                    durationUs: Long?
                ) {
                    samples.add(Triple(data.toString(Charsets.UTF_8), timeUs, durationUs))
                }

                override fun onFlush() = Unit

                override fun onRelease() = Unit
            }
        )
        extractor.init(output)
        try {
            while (extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) { }
            val track = output.trackOutputs.valueAt(0)
            assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, track.lastFormat!!.sampleMimeType)
            assertEquals(0, track.sampleCount)
            assertEquals(0, samples.size)
            subtitleInput.selectTrack(track.lastFormat!!.id)
            assertEquals(3, samples.size)
            samples.forEachIndexed { index, sample ->
                assertEquals("0,0,Default,,0,0,0,,SUBTITLE $index 简体繁體 with enough text to exercise deflate", sample.first)
                assertEquals(index * 2_000_000L, sample.second)
                assertEquals(2_000_000L, sample.third)
            }
        } finally {
            extractor.release()
        }
    }

    @Test(expected = ParserException::class)
    fun corruptZlib_isRejectedWithoutReturningBinaryText() {
        extractData(mkv(0, byteArrayOf(1, 2, 3)))
    }

    @Test(expected = ParserException::class)
    fun excessiveInflation_isBounded() {
        val bomb =
            ByteArrayOutputStream()
                .also { stream ->
                    DeflaterOutputStream(stream).use { it.write(ByteArray(17 * 1024 * 1024) { 65 }) }
                }.toByteArray()
        extractData(mkv(0, bomb))
    }

    private fun extract(compression: Int?): List<Pair<Long, String>> = extractData(mkv(compression))

    private fun extractData(data: ByteArray): List<Pair<Long, String>> {
        val extractor = LibassMatroskaExtractor(NoFonts)
        val output = FakeExtractorOutput()
        val input = FakeExtractorInput.Builder().setData(data).build()
        extractor.init(output)
        try {
            while (extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) {
                // Use the real EBML reader and text sample assembly, including NUL trimming.
            }
            val track = output.trackOutputs.valueAt(0)
            assertEquals(3, track.sampleCount)
            return (0 until track.sampleCount).map { index ->
                track.getSampleTimeUs(index) to String(track.getSampleData(index), Charsets.UTF_8)
            }
        } finally {
            extractor.release()
        }
    }

    private fun mkv(
        compression: Int?,
        payloadOverride: ByteArray? = null
    ): ByteArray {
        val header = "[Script Info]\nScriptType: v4.00+\n[V4+ Styles]\n[Events]\n"
        val encoding =
            if (compression == null) {
                byteArrayOf()
            } else {
                element(
                    0x6D80,
                    element(
                        0x6240,
                        element(
                            0x5034,
                            (if (compression == -1) byteArrayOf() else element(0x4254, byteArrayOf(compression.toByte()))) +
                                (if (compression == 3) element(0x4255, "0,".toByteArray()) else byteArrayOf())
                        )
                    )
                )
            }
        val track =
            element(
                0xAE,
                element(0xD7, byteArrayOf(1)) + element(0x83, byteArrayOf(0x11)) +
                    element(0x86, "S_TEXT/ASS".toByteArray()) + element(0x63A2, header.toByteArray()) + encoding
            )
        val blocks =
            (0 until 3)
                .map { index ->
                    val text = "0,0,Default,,0,0,0,,SUBTITLE $index 简体繁體 with enough text to exercise deflate"
                    val payload =
                        payloadOverride ?: when (compression) {
                            -1, 0 ->
                                ByteArrayOutputStream()
                                    .also { stream ->
                                        DeflaterOutputStream(stream).use { it.write(text.toByteArray()) }
                                    }.toByteArray()
                            3 -> text.drop(2).toByteArray()
                            else -> text.toByteArray()
                        }
                    val time = index * 2000
                    element(
                        0xA0,
                        element(0xA1, byteArrayOf(0x81.toByte(), (time shr 8).toByte(), time.toByte(), 0) + payload) +
                            element(0x9B, byteArrayOf(0x07, 0xD0.toByte()))
                    )
                }.fold(byteArrayOf()) { all, block -> all + block }
        return element(
            0x18538067,
            element(0x1549A966, element(0x2AD7B1, byteArrayOf(0x0F, 0x42, 0x40))) +
                element(0x1654AE6B, track) + element(0x1F43B675, element(0xE7, byteArrayOf(0)) + blocks)
        )
    }

    private fun element(
        id: Int,
        data: ByteArray
    ): ByteArray {
        val idBytes = (3 downTo 0).map { (id ushr (it * 8)).toByte() }.dropWhile { it == 0.toByte() }.toByteArray()
        val length = (1..4).first { data.size < (1 shl (it * 7)) - 1 }
        val size = data.size or (1 shl (length * 7))
        val sizeBytes = (length - 1 downTo 0).map { (size ushr (it * 8)).toByte() }.toByteArray()
        return idBytes + sizeBytes + data
    }

    private object NoFonts : Media3FontAttachmentSink {
        override fun reserve(size: Int): Boolean = false

        override fun cancel(size: Int) = Unit

        override fun onFont(
            name: String,
            data: ByteArray
        ) = Unit
    }
}
