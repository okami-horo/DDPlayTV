package com.xyoye.common_component.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xyoye.common_component.log.model.DebugToggleState
import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.LogRuntimeState
import com.xyoye.common_component.log.model.LogTag
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LogFileQualityInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cleanup()
    }

    @After
    fun tearDown() {
        cleanup()
    }

    @Test
    fun structuredLogsRemainCompactInFiles() {
        val writer = LogWriter(context)
        val runtime = LogRuntimeState(
            activePolicy = LogPolicy.debugSessionPolicy(minLevel = LogLevel.DEBUG, enableFile = true),
            debugToggleState = DebugToggleState.ON_CURRENT_SESSION,
            debugSessionEnabled = true
        )
        writer.updateRuntimeState(runtime)

        val events = listOf(
            LogEvent(
                level = LogLevel.INFO,
                module = LogModule.PLAYER,
                tag = LogTag(LogModule.PLAYER, "Engine"),
                message = "prepare playback",
                context = mapOf(
                    "scene" to "playback",
                    "sessionId" to "sess-1",
                    "requestId" to "req-1"
                )
            ),
            LogEvent(
                level = LogLevel.WARN,
                module = LogModule.NETWORK,
                tag = LogTag(LogModule.NETWORK, "Api"),
                message = "retry request",
                context = mapOf(
                    "scene" to "playback",
                    "errorCode" to "E-RATE",
                    "attempt" to "2"
                )
            ),
            LogEvent(
                level = LogLevel.DEBUG,
                module = LogModule.PLAYER,
                tag = LogTag(LogModule.PLAYER, "Buffer"),
                message = "buffer stats collected",
                context = mapOf(
                    "scene" to "playback",
                    "detail" to "long detail line1\nline2",
                    "extraA" to "valueA",
                    "extraB" to "valueB",
                    "extraC" to "valueC",
                    "extraD" to "valueD",
                    "extraE" to "valueE",
                    "noisy" to "valueF"
                )
            )
        )

        events.forEach { writer.submit(it) }
        Thread.sleep(600)

        val lines = LogPaths.currentLogFile(context).takeIf { it.exists() }?.readLines().orEmpty()

        Assert.assertEquals(events.size, lines.size)
        Assert.assertTrue(lines.all { it.contains("level=") && it.contains("module=") })
        Assert.assertTrue(lines.any { it.contains("ctx_scene=") && it.contains("ctx_requestId=req-1") })
        Assert.assertTrue(lines.any { it.contains("ctx_errorCode=E-RATE") })
        Assert.assertTrue(lines.any { it.contains("ctx_dropped=") })
        Assert.assertTrue(lines.none { it.contains("noisy=valueF") })
    }

    private fun cleanup() {
        val dir: File = LogPaths.logDirectory(ApplicationProvider.getApplicationContext())
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}
