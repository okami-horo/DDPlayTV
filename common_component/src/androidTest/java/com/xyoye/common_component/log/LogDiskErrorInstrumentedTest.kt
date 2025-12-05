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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LogDiskErrorInstrumentedTest {

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
    fun enterDisabledStateAfterFileWriteFailure() {
        val errors = mutableListOf<Throwable>()
        val latch = CountDownLatch(1)
        val faultyManager = object : LogFileManager(context) {
            override fun appendLine(line: String) {
                latch.countDown()
                throw IOException("simulate disk full")
            }
        }
        val writer = LogWriter(
            context = context,
            fileManager = faultyManager
        ) { error ->
            errors.add(error)
            latch.countDown()
        }

        val runtimeState = LogRuntimeState(
            activePolicy = LogPolicy.debugSessionPolicy(),
            debugToggleState = DebugToggleState.ON_CURRENT_SESSION,
            debugSessionEnabled = true
        )
        writer.updateRuntimeState(runtimeState)

        writer.submit(
            LogEvent(
                level = LogLevel.ERROR,
                module = LogModule.CORE,
                message = "trigger disk error"
            )
        )

        latch.await(2, TimeUnit.SECONDS)

        val updatedState = writer.currentStateForTest()
        assertEquals(DebugToggleState.DISABLED_DUE_TO_ERROR, updatedState.debugToggleState)
        assertFalse(updatedState.debugSessionEnabled)
        assertTrue(errors.isNotEmpty())
        assertFalse(LogPaths.logDirectory(context).exists())
    }

    private fun cleanup() {
        val dir: File = LogPaths.logDirectory(ApplicationProvider.getApplicationContext())
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}
