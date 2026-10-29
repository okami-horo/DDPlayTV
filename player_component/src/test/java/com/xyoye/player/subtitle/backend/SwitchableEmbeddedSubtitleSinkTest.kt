package com.xyoye.player.subtitle.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchableEmbeddedSubtitleSinkTest {
    @Test
    fun releaseWhileMuted_dropsCachedFormatWithoutClearingExternalTrack() {
        val delegate = RecordingEmbeddedSubtitleSink()
        val sink = SwitchableEmbeddedSubtitleSink(delegate)
        sink.onFormat("old track".toByteArray())
        sink.setEnabled(false)

        sink.onRelease()
        sink.setEnabled(true)

        assertEquals(0, delegate.releaseCount)
        assertEquals(1, delegate.receivedFormats.size)
    }

    @Test
    fun releaseThenNewFormat_startsAnotherEmbeddedTrack() {
        val delegate = RecordingEmbeddedSubtitleSink()
        val sink = SwitchableEmbeddedSubtitleSink(delegate)
        sink.onFormat("first".toByteArray())
        sink.onRelease()
        sink.onFormat("second".toByteArray())
        sink.onSample("event".toByteArray(), 1_000L, 2_000L)

        assertEquals(1, delegate.releaseCount)
        assertEquals(2, delegate.receivedFormats.size)
        assertEquals(1, delegate.receivedSamples.size)
    }

    @Test
    fun onFormat_replaysCachedCodecPrivateWhenReEnabled() {
        val delegate = RecordingEmbeddedSubtitleSink()
        val sink = SwitchableEmbeddedSubtitleSink(delegate)
        val codecPrivate = "header".toByteArray(Charsets.UTF_8)

        sink.setEnabled(false)
        sink.onFormat(codecPrivate)
        assertTrue(delegate.receivedFormats.isEmpty())

        sink.setEnabled(true)

        assertEquals(1, delegate.receivedFormats.size)
        assertTrue(delegate.receivedFormats.single()?.contentEquals(codecPrivate) == true)
    }

    @Test
    fun onSampleAndFlush_areMutedWhenDisabled() {
        val delegate = RecordingEmbeddedSubtitleSink()
        val sink = SwitchableEmbeddedSubtitleSink(delegate)
        val payload = "dialogue".toByteArray(Charsets.UTF_8)

        sink.setEnabled(false)
        sink.onSample(payload, 1_000L, 2_000L)
        sink.onFlush()

        assertTrue(delegate.receivedSamples.isEmpty())
        assertEquals(0, delegate.flushCount)

        sink.setEnabled(true)
        sink.onSample(payload, 3_000L, 4_000L)
        sink.onFlush()

        assertEquals(1, delegate.receivedSamples.size)
        assertEquals(1, delegate.flushCount)
        assertTrue(
            delegate.receivedSamples
                .single()
                .first
                .contentEquals(payload)
        )
    }

    private class RecordingEmbeddedSubtitleSink : EmbeddedSubtitleSink {
        val receivedFormats = mutableListOf<ByteArray?>()
        val receivedSamples = mutableListOf<Triple<ByteArray, Long, Long?>>()
        var flushCount: Int = 0
        var releaseCount: Int = 0

        override fun onFormat(codecPrivate: ByteArray?) {
            receivedFormats += codecPrivate?.copyOf()
        }

        override fun onSample(
            data: ByteArray,
            timeUs: Long,
            durationUs: Long?
        ) {
            receivedSamples += Triple(data.copyOf(), timeUs, durationUs)
        }

        override fun onFlush() {
            flushCount++
        }

        override fun onRelease() {
            releaseCount++
        }
    }
}
