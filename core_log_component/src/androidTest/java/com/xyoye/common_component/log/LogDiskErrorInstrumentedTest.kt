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
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testRoot = File(context.cacheDir, "log_test")
        LogPaths.overrideDownloadRootForTests(testRoot)
        cleanup()
    }

    @After
    fun tearDown() {
        cleanup()
        LogPaths.overrideDownloadRootForTests(null)
    }

    @Test
    fun enterDisabledStateAfterFileWriteFailure() {
        val errors = mutableListOf<Throwable>()
        val writeAttempted = CountDownLatch(1)
        val errorHandled = CountDownLatch(1)
        val faultyManager =
            object : LogFileManager(context) {
                override fun appendLine(line: String) {
                    writeAttempted.countDown()
                    throw IOException("simulate disk full")
                }
            }
        val writer =
            LogWriter(
                context = context,
                fileManager = faultyManager,
            ) { error ->
                errors.add(error)
                errorHandled.countDown()
            }

        val runtimeState =
            LogRuntimeState(
                activePolicy = LogPolicy.debugSessionPolicy(),
                debugToggleState = DebugToggleState.ON_CURRENT_SESSION,
                debugSessionEnabled = true,
            )
        writer.updateRuntimeState(runtimeState)

        writer.submit(
            LogEvent(
                level = LogLevel.ERROR,
                module = LogModule.CORE,
                message = "trigger disk error",
            ),
        )

        // 等待写入被尝试且错误被处理（handleFileError 运行完毕）
        writeAttempted.await(2, TimeUnit.SECONDS)
        errorHandled.await(2, TimeUnit.SECONDS)

        val updatedState = writer.currentStateForTest()
        assertEquals(DebugToggleState.DISABLED_DUE_TO_ERROR, updatedState.debugToggleState)
        assertFalse(updatedState.debugSessionEnabled)
        assertTrue(errors.isNotEmpty())

        // 磁盘错误后不再写文件，但日志目录/文件可能已在 prepare 阶段创建，应保持为空
        val dir = LogPaths.logDirectory(context)
        assertTrue(dir.exists())
        assertEquals(0, LogPaths.currentLogFile(context).length())
        assertEquals(0, LogPaths.previousLogFile(context).length())
    }

    private fun cleanup() {
        val dir: File = LogPaths.logDirectory(ApplicationProvider.getApplicationContext())
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}
