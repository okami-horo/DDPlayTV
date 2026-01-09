package com.xyoye.common_component.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LogFileManagerScanTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun scanExistingLogFilesWithoutCreatingNewOnes() {
        val filesRoot = tempFolder.newFolder("files_root")
        val downloadsRoot = tempFolder.newFolder("downloads_root")
        val context = TestLogContext(filesRoot)
        LogPaths.overrideDownloadRootForTests(downloadsRoot)
        try {
            val label = runCatching { context.applicationInfo.loadLabel(context.packageManager) }.getOrNull()
            val appName = label?.toString()?.trim().takeIf { it.isNullOrBlank().not() } ?: context.packageName
            val logDir = File(File(downloadsRoot, appName), LogPaths.LOG_DIR_NAME).apply { mkdirs() }
            val current = File(logDir, LogPaths.CURRENT_LOG_FILE_NAME).apply { writeText("a") }
            val previous = File(logDir, LogPaths.PREVIOUS_LOG_FILE_NAME).apply { writeText("bcd") }
            File(logDir, "ignored.log").writeText("noise")

            val manager = LogFileManager(context)

            val files = manager.scanLogFiles()

            assertEquals(2, files.size)
            val currentMeta = files.first { it.fileName == LogPaths.CURRENT_LOG_FILE_NAME }
            val previousMeta = files.first { it.fileName == LogPaths.PREVIOUS_LOG_FILE_NAME }
            assertEquals(current.absolutePath, currentMeta.path)
            assertEquals(1, currentMeta.sizeBytes)
            assertEquals(previous.absolutePath, previousMeta.path)
            assertEquals(3, previousMeta.sizeBytes)
        } finally {
            LogPaths.overrideDownloadRootForTests(null)
        }
    }

    @Test
    fun scanEmptyDirectoryReturnsEmptyAndKeepsFileSystemUntouched() {
        val filesRoot = tempFolder.newFolder("files_root_empty")
        val downloadsRoot = tempFolder.newFolder("downloads_root_empty")
        val context = TestLogContext(filesRoot)
        LogPaths.overrideDownloadRootForTests(downloadsRoot)
        try {
            val manager = LogFileManager(context)

            val files = manager.scanLogFiles()

            assertTrue(files.isEmpty())
            val label = runCatching { context.applicationInfo.loadLabel(context.packageManager) }.getOrNull()
            val appName = label?.toString()?.trim().takeIf { it.isNullOrBlank().not() } ?: context.packageName
            assertFalse(File(File(downloadsRoot, appName), LogPaths.LOG_DIR_NAME).exists())
        } finally {
            LogPaths.overrideDownloadRootForTests(null)
        }
    }
}
