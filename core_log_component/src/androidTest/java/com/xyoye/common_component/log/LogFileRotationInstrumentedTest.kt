package com.xyoye.common_component.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LogFileRotationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testRoot = File(context.cacheDir, "log_rotation_test")
        LogPaths.overrideDownloadRootForTests(testRoot)
        cleanup()
    }

    @After
    fun tearDown() {
        cleanup()
        LogPaths.overrideDownloadRootForTests(null)
    }

    @Test
    fun mergeCurrentLogIntoOldOnPrepare() {
        val dir = LogPaths.logDirectory(context)
        dir.mkdirs()
        val current = LogPaths.currentLogFile(context)
        val previous = LogPaths.previousLogFile(context)

        previous.parentFile?.mkdirs()
        previous.writeText("old\n")
        current.writeText("current\n")

        val manager = LogFileManager(context)
        manager.prepare()

        assertEquals("old\ncurrent\n", previous.readText())
        assertEquals("", current.readText())
        assertEquals(dir.absolutePath, manager.logDirectory().absolutePath)
    }

    private fun cleanup() {
        val dir: File = LogPaths.logDirectory(ApplicationProvider.getApplicationContext())
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}
