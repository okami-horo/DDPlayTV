package com.xyoye.player.kernel.impl.media3

import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3SubtitleInputTest {
    @Test
    fun replacedDecoder_cannotReclaimTrackWithALateFormat() {
        val input = Media3SubtitleInput()
        val output = RecordingSink()
        input.beginMedia()
        input.setSink(output)
        val oldTrack = input.createDecoderSink()!!
        oldTrack.onFormat(byteArrayOf(1))
        val newTrack = input.createDecoderSink()!!
        newTrack.onFormat(byteArrayOf(2))
        oldTrack.onFormat(byteArrayOf(1))
        oldTrack.onSample(byteArrayOf(1), 1000, 1000)
        newTrack.onSample(byteArrayOf(2), 2000, 1000)
        assertEquals(2, output.header!!.single().toInt())
        assertEquals(listOf(2000L), output.samples)
    }

    @Test
    fun switchingMedia_rejectsLateFontsSamplesAndReleases() {
        val input = Media3SubtitleInput()
        val output = RecordingSink()
        input.setSink(output)
        val oldFonts = input.beginMedia()
        val oldTrack = input.createDecoderSink()!!
        oldTrack.onFormat(byteArrayOf(1))
        assertTrue(oldFonts.reserve(4))

        val newFonts = input.beginMedia()
        val newTrack = input.createDecoderSink()!!
        newTrack.onFormat(byteArrayOf(2))
        assertTrue(newFonts.reserve(4))
        newFonts.onFont("new.ttf", byteArrayOf(2, 2, 2, 2))
        oldFonts.onFont("old.ttf", byteArrayOf(1, 1, 1, 1))
        oldTrack.onSample(byteArrayOf(1), 0, 1000)
        oldTrack.onFlush()
        oldTrack.onRelease()
        newTrack.onSample(byteArrayOf(2), 5000, 1000)

        assertEquals(listOf("new.ttf"), output.fonts)
        assertEquals(listOf(5000L), output.samples)
        assertEquals(0, output.releases)
        assertEquals(0, output.flushes)
        assertFalse(oldFonts.reserve(4))
    }

    @Test
    fun fontsSurviveTrackReplacementSeekAndSinkRecreation() {
        val input = Media3SubtitleInput()
        val fonts = input.beginMedia()
        assertTrue(fonts.reserve(4))
        fonts.onFont("cached.ttf", byteArrayOf(0, 1, 0, 0))
        val first = RecordingSink()
        input.setSink(first)
        val oldTrack = input.createDecoderSink()!!
        oldTrack.onFormat(byteArrayOf(1))
        oldTrack.onFlush()
        val replacement = input.createDecoderSink()!!
        replacement.onFormat(byteArrayOf(2))
        oldTrack.onRelease()
        input.setSink(null)
        val second = RecordingSink()
        input.setSink(second)
        replacement.onSample(byteArrayOf(2), 8000, 1000)

        assertEquals(listOf("cached.ttf"), first.fonts)
        assertEquals(listOf("cached.ttf"), second.fonts)
        assertEquals(2, second.header!!.single().toInt())
        assertEquals(listOf(8000L), second.samples)
        assertEquals(0, first.releases)
    }

    @Test
    fun budgetIncludesPendingAndRegisteredFonts() {
        val input = Media3SubtitleInput(singleFontLimit = 8, mediaFontLimit = 12)
        val fonts = input.beginMedia()
        assertTrue(fonts.reserve(8))
        assertTrue(fonts.reserve(4))
        assertFalse(fonts.reserve(1))
        fonts.cancel(4)
        fonts.onFont("one.ttf", ByteArray(8))
        assertFalse(fonts.reserve(5))
        assertTrue(fonts.reserve(4))
        fonts.cancel(4)
        val nextMedia = input.beginMedia()
        assertTrue(nextMedia.reserve(8))
        nextMedia.onFont("one.ttf", ByteArray(8))
        assertTrue(nextMedia.reserve(4))
        nextMedia.onFont("small.ttf", ByteArray(4))
        assertFalse(nextMedia.reserve(1))
    }

    @Test
    fun preloadedMedia_replaysItsOwnFontsAndSamplesOnlyWhenActivated() {
        val output = RecordingSink()
        val current = Media3SubtitleInput()
        current.beginMedia()
        current.setSink(output)
        val oldTrack = current.createDecoderSink()!!
        oldTrack.onFormat(byteArrayOf(1))
        oldTrack.onSample(byteArrayOf(1), 1000, 2000)

        val next = Media3SubtitleInput()
        val nextFonts = next.beginMedia()
        val nextTrack = next.createDecoderSink(allowDetached = true)!!
        nextTrack.onFormat(byteArrayOf(2))
        nextTrack.onSample(byteArrayOf(2), 3000, 2000)
        assertTrue(nextFonts.reserve(4))
        nextFonts.onFont("next.ttf", byteArrayOf(0, 1, 0, 0))
        assertEquals(listOf(1000L), output.samples)
        current.setSink(null)
        next.setSink(output)
        oldTrack.onSample(byteArrayOf(1), 5000, 2000)
        oldTrack.onRelease()
        assertEquals(listOf(3000L), output.samples)
        assertEquals(listOf("next.ttf"), output.fonts)
        assertEquals(0, output.releases)
    }

    @Test
    fun duplicateFontReservations_doNotConsumeTheBudgetAgain() {
        val input = Media3SubtitleInput(singleFontLimit = 8, mediaFontLimit = 16)
        val output = RecordingSink()
        input.setSink(output)
        val fonts = input.beginMedia()
        repeat(4) {
            assertTrue(fonts.reserve(8))
            fonts.onFont("font.ttf", ByteArray(8))
        }
        assertEquals(listOf("font.ttf"), output.fonts)
        assertTrue(fonts.reserve(8))
    }

    private class RecordingSink : EmbeddedSubtitleSink {
        val fonts = mutableListOf<String>()
        val samples = mutableListOf<Long>()
        var header: ByteArray? = null
        var releases = 0
        var flushes = 0

        override fun onMediaChanged() {
            fonts.clear()
            samples.clear()
            header = null
        }

        override fun onFont(
            name: String,
            data: ByteArray
        ) {
            fonts.add(name)
        }

        override fun onFormat(codecPrivate: ByteArray?) {
            header = codecPrivate
        }

        override fun onSample(
            data: ByteArray,
            timeUs: Long,
            durationUs: Long?
        ) {
            samples.add(timeUs)
        }

        override fun onFlush() {
            flushes++
        }

        override fun onRelease() {
            releases++
        }
    }
}
