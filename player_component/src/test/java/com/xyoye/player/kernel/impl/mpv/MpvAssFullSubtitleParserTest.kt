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
            sample.data.decodeToString(),
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
        assertEquals("0,0,Default,,0,0,0,,Hello", samples[0].data.decodeToString())
        assertEquals(3500L, samples[1].timecodeMs)
        assertEquals(500L, samples[1].durationMs)
        assertEquals("1,0,Default,,0,0,0,,World", samples[1].data.decodeToString())
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
            samples[0].data.decodeToString(),
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

    @Test
    fun parse_resetClearsDedupStateForTimelineChanges() {
        val parser = MpvAssFullSubtitleParser()
        val dialogue = buildDialogue(startMs = 1_000L, endMs = 2_000L, text = "Hello")

        assertEquals(1, parser.parse(dialogue).size)
        assertTrue(parser.parse(dialogue).isEmpty())

        parser.reset()

        val replayed = parser.parse(dialogue)
        assertEquals(1, replayed.size)
        assertEquals("0,0,Default,,0,0,0,,Hello", replayed[0].data.decodeToString())
    }

    @Test
    fun parse_prunesStaleHistoryDuringLongPlayback() {
        val parser = MpvAssFullSubtitleParser()

        repeat(700) { index ->
            val startMs = index * 2_000L
            val endMs = startMs + 1_000L
            val dialogue = buildDialogue(startMs = startMs, endMs = endMs, text = "Line $index")
            val samples = parser.parse(dialogue)
            assertEquals(1, samples.size)
        }

        assertTrue(parser.historySize() <= 512)
    }

    private fun buildDialogue(
        startMs: Long,
        endMs: Long,
        text: String
    ): String = "Dialogue: 0,${toAssTime(startMs)},${toAssTime(endMs)},Default,,0,0,0,,$text"

    private fun toAssTime(timeMs: Long): String {
        val totalCentiseconds = timeMs / 10L
        val centiseconds = totalCentiseconds % 100L
        val totalSeconds = totalCentiseconds / 100L
        val seconds = totalSeconds % 60L
        val totalMinutes = totalSeconds / 60L
        val minutes = totalMinutes % 60L
        val hours = totalMinutes / 60L
        return "%d:%02d:%02d.%02d".format(hours, minutes, seconds, centiseconds)
    }
}
