package com.xyoye.player.kernel.impl.mpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvAssFullSubtitleParserTest {
    @Test
    fun parse_returnsEmptyOnNullOrBlankInput() {
        val parser = MpvAssFullSubtitleParser()
        assertTrue(parser.parse(null).isEmpty())
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse("   ").isEmpty())
    }

    @Test
    fun parse_parsesSingleDialogueLine() {
        val parser = MpvAssFullSubtitleParser()
        val samples =
            parser.parse(
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello",
            )

        assertEquals(1, samples.size)
        val sample = samples[0]
        assertEquals(1000L, sample.timecodeMs)
        assertEquals(1000L, sample.durationMs)
        assertEquals(
            "0,0,Default,,0,0,0,,Hello",
            sample.data.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun parse_parsesMultipleDialogueLines() {
        val parser = MpvAssFullSubtitleParser()
        val samples =
            parser.parse(
                """
                Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello
                Dialogue: 0,0:00:03.50,0:00:04.00,Default,,0,0,0,,World
                """.trimIndent(),
            )

        assertEquals(2, samples.size)
        assertEquals(1000L, samples[0].timecodeMs)
        assertEquals(1000L, samples[0].durationMs)
        assertEquals("0,0,Default,,0,0,0,,Hello", samples[0].data.toString(Charsets.UTF_8))
        assertEquals(3500L, samples[1].timecodeMs)
        assertEquals(500L, samples[1].durationMs)
        assertEquals("1,0,Default,,0,0,0,,World", samples[1].data.toString(Charsets.UTF_8))
    }

    @Test
    fun parse_keepsCommasInsideTextField() {
        val parser = MpvAssFullSubtitleParser()
        val samples =
            parser.parse(
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello, world",
            )

        assertEquals(1, samples.size)
        assertEquals(
            "0,0,Default,,0,0,0,,Hello, world",
            samples[0].data.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun parse_parsesEdgeTimecodes() {
        val parser = MpvAssFullSubtitleParser()
        val samples =
            parser.parse(
                "Dialogue: 0,9:59:59.99,10:00:00.00,Default,,0,0,0,,Edge",
            )

        assertEquals(1, samples.size)
        assertEquals(35999990L, samples[0].timecodeMs)
        assertEquals(10L, samples[0].durationMs)
    }
}

