package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import com.xyoye.common_component.media3.testing.Media3Dependent
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Media3Dependent("Prevent regressions that break libass SSA decoder initialization")
class LibassSsaStreamDecoderTest {
    @Test
    fun concurrentDecoders_doNotShareInputBuffers() {
        val first = createDecoder()
        val second = createDecoder()
        try {
            assertNotSame(first.dequeueInputBuffer(), second.dequeueInputBuffer())
        } finally {
            first.release()
            second.release()
        }
    }

    @Test
    fun release_notifiesTheSinkThatOwnedTheTrack() {
        val first = TestEmbeddedSink()
        val replacement = TestEmbeddedSink()
        var activeSink: EmbeddedSubtitleSink = first
        val decoder = LibassSsaStreamDecoder(Format.Builder().setSampleMimeType("text/x-ssa").build()) { activeSink }
        activeSink = replacement

        decoder.release()

        assertEquals(1, first.releaseCount)
        assertEquals(0, replacement.releaseCount)
    }

    @Test
    fun constructor_initializesWithoutArrayStoreException() {
        val decoder =
            LibassSsaStreamDecoder(
                format =
                    Format
                        .Builder()
                        .setSampleMimeType("text/x-ssa")
                        .build(),
                sinkProvider = { null },
            )

        decoder.release()
    }

    @Test
    fun normalizeSampleTimeUs_alignsSampleRelativeTimeline() {
        val decoder = createDecoder()

        assertEquals(500_000L, decoder.normalizeSampleTimeUs(1_000_000_500_000L, 1_000_000_000_000L))
        assertEquals(1_000_000_500_000L, decoder.normalizeSampleTimeUs(1_000_000_500_000L, Format.OFFSET_SAMPLE_RELATIVE))
        assertEquals(500_000L, decoder.normalizeSampleTimeUs(500_000L, Format.OFFSET_SAMPLE_RELATIVE))
        assertEquals(1_000_000_000_000L, decoder.normalizeSampleTimeUs(1_000_000_000_000L, C.TIME_UNSET))

        decoder.release()
    }

    @Test
    fun normalizeSampleTimeUs_doesNotGuessAnOffsetFromLargeContentTimestamps() {
        val decoder = createDecoder()
        try {
            assertEquals(
                2_000_000_400_000L,
                decoder.normalizeSampleTimeUs(2_000_000_400_000L, Format.OFFSET_SAMPLE_RELATIVE)
            )
            assertEquals(
                14_500_000L,
                decoder.normalizeSampleTimeUs(4_500_000L + 873_001_234L, 873_001_234L - 10_000_000L)
            )
            assertEquals(-2_000_000L, decoder.normalizeSampleTimeUs(873_001_234L, 875_001_234L))
        } finally {
            decoder.release()
        }
    }

    @Test
    fun normalizeSampleTimeUs_usesExplicitOffsetsAcrossTimelines() {
        val decoder = createDecoder()

        assertEquals(200_000L, decoder.normalizeSampleTimeUs(2_000_000_200_000L, 2_000_000_000_000L))
        assertEquals(400_000L, decoder.normalizeSampleTimeUs(2_000_000_400_000L, 2_000_000_000_000L))
        assertEquals(300_000L, decoder.normalizeSampleTimeUs(300_000L, Format.OFFSET_SAMPLE_RELATIVE))

        decoder.release()
    }

    @Test
    fun normalizeSampleForTest_unwrapsMedia3MatroskaDialoguePrefix() {
        val decoder = createDecoder()
        val payload =
            "Dialogue: 0:00:00:00,0:00:01:69,0,0,Default,,0,0,0,,Test line"
                .toByteArray(Charsets.UTF_8)

        val (normalized, durationUs) = decoder.normalizeSampleForTest(payload)

        assertEquals("0,0,Default,,0,0,0,,Test line", normalized.toString(Charsets.UTF_8))
        assertEquals(1_690_000L, durationUs)
        decoder.release()
    }

    @Test
    fun normalizeSampleForTest_keepsRawPayloadWhenNotWrapped() {
        val decoder = createDecoder()
        val payload = "0,0,Default,,0,0,0,,Raw line".toByteArray(Charsets.UTF_8)

        val (normalized, durationUs) = decoder.normalizeSampleForTest(payload)

        assertTrue(normalized.contentEquals(payload))
        assertNull(durationUs)
        decoder.release()
    }

    @Test
    fun constructor_usesExistingEventsCodecPrivateWhenAvailable() {
        val sink = TestEmbeddedSink()
        val format =
            Format
                .Builder()
                .setSampleMimeType("text/x-ssa")
                .setInitializationData(
                    listOf(
                        "Format: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
                            .toByteArray(Charsets.UTF_8),
                        "[Script Info]\nTitle: Test\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                            .toByteArray(Charsets.UTF_8),
                    ),
                ).build()

        val decoder = LibassSsaStreamDecoder(format, sinkProvider = { sink })

        assertEquals(
            String(format.initializationData[1], Charsets.UTF_8),
            String(sink.lastCodecPrivate ?: ByteArray(0), Charsets.UTF_8),
        )
        decoder.release()
    }

    @Test
    fun constructor_mergesCodecPrivateWhenEventsSectionMissing() {
        val sink = TestEmbeddedSink()
        val dialogueFormat =
            "Format: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
                .toByteArray(Charsets.UTF_8)
        val header = "[Script Info]\nTitle: Test\n".toByteArray(Charsets.UTF_8)
        val format =
            Format
                .Builder()
                .setSampleMimeType("text/x-ssa")
                .setInitializationData(listOf(dialogueFormat, header))
                .build()

        val decoder = LibassSsaStreamDecoder(format, sinkProvider = { sink })
        val merged = String(sink.lastCodecPrivate ?: ByteArray(0), Charsets.UTF_8)

        assertTrue(merged.contains("[Events]"))
        assertTrue(merged.contains(String(dialogueFormat, Charsets.UTF_8)))
        decoder.release()
    }

    @Test
    fun constructor_mergesCodecPrivateWhenFormatBelongsToStyleSection() {
        val sink = TestEmbeddedSink()
        val dialogueFormat =
            "Format: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
                .toByteArray(Charsets.UTF_8)
        val styleOnlyHeader =
            "[Script Info]\nTitle: Test\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour\n"
                .toByteArray(Charsets.UTF_8)
        val format =
            Format
                .Builder()
                .setSampleMimeType("text/x-ssa")
                .setInitializationData(listOf(dialogueFormat, styleOnlyHeader))
                .build()

        val decoder = LibassSsaStreamDecoder(format, sinkProvider = { sink })
        val merged = String(sink.lastCodecPrivate ?: ByteArray(0), Charsets.UTF_8)

        assertTrue(merged.contains("[Events]"))
        assertTrue(merged.contains(String(dialogueFormat, Charsets.UTF_8)))
        assertFalse(merged == String(styleOnlyHeader, Charsets.UTF_8))
        decoder.release()
    }

    private fun createDecoder(): LibassSsaStreamDecoder =
        LibassSsaStreamDecoder(
            format =
                Format
                    .Builder()
                    .setSampleMimeType("text/x-ssa")
                    .build(),
            sinkProvider = { null },
        )

    private class TestEmbeddedSink : EmbeddedSubtitleSink {
        var lastCodecPrivate: ByteArray? = null
        var releaseCount = 0

        override fun onFormat(codecPrivate: ByteArray?) {
            lastCodecPrivate = codecPrivate
        }

        override fun onSample(
            data: ByteArray,
            timeUs: Long,
            durationUs: Long?
        ) = Unit

        override fun onFlush() = Unit

        override fun onRelease() {
            releaseCount++
        }
    }
}
