package com.xyoye.common_component.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LogFileManagerPathTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun logFilesLocatedUnderFixedDirectory() {
        val filesRoot = tempFolder.newFolder("files_root")
        val context = TestLogContext(filesRoot)
        val manager = LogFileManager(context)

        manager.prepare()

        val expectedDir = File(filesRoot, LogPaths.LOG_DIR_NAME)
        assertEquals(expectedDir.absolutePath, manager.logDirectory().absolutePath)
        assertTrue(expectedDir.exists())
        assertTrue(manager.currentLogFile().exists())
        assertTrue(manager.previousLogFile().exists())
        assertEquals(LogPaths.CURRENT_LOG_FILE_NAME, manager.currentLogFile().name)
        assertEquals(LogPaths.PREVIOUS_LOG_FILE_NAME, manager.previousLogFile().name)
    }
}
