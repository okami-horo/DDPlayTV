package com.xyoye.common_component.log

import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.log.model.LogTag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogFormatterHighSignalTest {

    private val formatter = LogFormatter()

    @Test
    fun fileFormatHighlightsKeyContextAndDropsNoise() {
        val context = linkedMapOf(
            "scene" to "playback",
            "errorCode" to "E001",
            "sessionId" to "sess-1",
            "requestId" to "req-9",
            "detail" to "line1\nline2",
            "extraA" to "valueA",
            "extraB" to "valueB",
            "extraC" to "valueC",
            "extraD" to "valueD",
            "extraE" to "valueE",
            "noisy" to "valueF"
        )
        val event = LogEvent(
            timestamp = 1_700_000_000_000,
            level = LogLevel.DEBUG,
            module = LogModule.PLAYER,
            tag = LogTag(LogModule.PLAYER, "Renderer"),
            message = "renderer failed\nretrying",
            context = context,
            throwable = IllegalStateException("boom"),
            threadName = "LogThread",
            sequenceId = 42
        )

        val line = formatter.format(event)

        assertTrue(line.startsWith("time="))
        assertTrue(line.contains("level=DEBUG"))
        assertTrue(line.contains("module=player"))
        assertTrue(line.contains("tag=player:Renderer"))
        assertTrue(line.contains("thread=LogThread"))
        assertTrue(line.contains("seq=42"))
        assertTrue(line.contains("ctx_scene=playback"))
        assertTrue(line.contains("ctx_errorCode=E001"))
        assertTrue(line.contains("ctx_sessionId=sess-1"))
        assertTrue(line.contains("ctx_requestId=req-9"))
        assertTrue(line.contains("ctx_dropped=1"))
        assertTrue(line.contains("context={detail=line1 line2,extraA=valueA,extraB=valueB,extraC=valueC,extraD=valueD,extraE=valueE}"))
        assertTrue(line.contains("throwable=java.lang.IllegalStateException"))
        assertTrue(line.contains("msg=\"renderer failed retrying\""))
        assertFalse(line.contains("noisy=valueF"))
    }
}
