package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.Format
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibassSubtitleDecoderFactoryTest {
    @Test
    fun creatingADecoder_acquiresOwnershipOnceAndQueriesDoNotReacquireIt() {
        val input = Media3SubtitleInput()
        val output = RecordingSink()
        input.beginMedia()
        input.setSink(output)
        var acquisitions = 0
        val factory =
            LibassSubtitleDecoderFactory(sinkAvailable = { input.hasSink() }) {
                acquisitions++
                input.createDecoderSink()
            }
        val format = Format.Builder().setSampleMimeType("text/x-ssa").build()
        val decoder = factory.createDecoder(format)
        try {
            repeat(3) { assertTrue(factory.supportsFormat(format)) }
            assertEquals(1, acquisitions)
        } finally {
            decoder.release()
        }
        assertEquals(1, output.releases)
    }

    @Test
    fun preloadedSource_usesItsDetachedInputWithoutAcquiringFallbackOwnership() {
        val input = Media3SubtitleInput()
        input.beginMedia()
        val factory =
            LibassSubtitleDecoderFactory(sinkAvailable = { false }) {
                throw AssertionError("A tagged source must not acquire the fallback sink")
            }
        factory.useSource(input)
        val format = Format.Builder().setSampleMimeType("text/x-ssa").build()
        assertTrue(factory.supportsFormat(format))
        val decoder = factory.createDecoder(format)
        val output = RecordingSink()
        input.setSink(output)
        decoder.release()
        assertEquals(1, output.releases)
    }

    @Test
    fun capabilityQueries_doNotAcquireSinkOrInvalidateAnActiveDecoder() {
        val input = Media3SubtitleInput()
        val output = RecordingSink()
        input.beginMedia()
        input.setSink(output)
        var acquisitions = 0
        val factory =
            LibassSubtitleDecoderFactory(sinkAvailable = { input.hasSink() }) {
                acquisitions++
                input.createDecoderSink()
            }
        val decoder = input.createDecoderSink()!!
        decoder.onFormat(byteArrayOf(1))
        val ass = Format.Builder().setSampleMimeType("text/x-ssa").build()

        repeat(3) {
            assertTrue(factory.hasSink())
            assertTrue(factory.supportsFormat(ass))
        }
        decoder.onSample(byteArrayOf(1), 1000, 2000)

        assertEquals(0, acquisitions)
        assertEquals(listOf(1000L), output.samples)
    }

    private class RecordingSink : EmbeddedSubtitleSink {
        val samples = mutableListOf<Long>()
        var releases = 0

        override fun onFormat(codecPrivate: ByteArray?) = Unit

        override fun onSample(
            data: ByteArray,
            timeUs: Long,
            durationUs: Long?
        ) {
            samples.add(timeUs)
        }

        override fun onFlush() = Unit

        override fun onRelease() {
            releases++
        }
    }
}
