package com.xyoye.common_component.log

import android.content.Context
import android.os.Environment
import androidx.annotation.VisibleForTesting
import java.io.File

/**
 * 集中管理日志相关的路径与文件名，避免字符串散落。
 *
 * 默认将日志写入「Download/<应用名>/logs」目录：
 * - 例如：/sdcard/Download/<app-name>/logs/
 * 如外部目录不可用，则回退到内部 files/logs。
 */
object LogPaths {
    const val LOG_DIR_NAME = "logs"
    const val CURRENT_LOG_FILE_NAME = "log.txt"
    const val PREVIOUS_LOG_FILE_NAME = "log_old.txt"
    @Volatile
    internal var downloadRootOverride: File? = null

    /**
     * 返回日志目录：
     * - 优先使用 Download/<应用名>/logs
     * - 若该路径不可用，则回退到 context.filesDir/logs
     */
    fun logDirectory(context: Context): File {
        val baseDir = downloadRootDirectory()
        val appDirName = resolveAppFolderName(context)
        val appRoot = baseDir?.let { File(it, appDirName) }
        return if (appRoot != null) {
            File(appRoot, LOG_DIR_NAME)
        } else {
            File(context.filesDir, LOG_DIR_NAME)
        }
    }

    fun logRelativePath(context: Context): String {
        val appDirName = resolveAppFolderName(context)
        return "${Environment.DIRECTORY_DOWNLOADS}/$appDirName/$LOG_DIR_NAME/"
    }

    internal fun downloadRootDirectory(): File? =
        downloadRootOverride ?: runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()

    @VisibleForTesting
    fun overrideDownloadRootForTests(root: File?) {
        downloadRootOverride = root
    }

    private fun resolveAppFolderName(context: Context): String {
        val label = runCatching { context.applicationInfo.loadLabel(context.packageManager) }.getOrNull()
        val appName = label?.toString()?.trim()
        val resolved = if (appName.isNullOrBlank()) context.packageName else appName
        return resolved.replace("/", "_").replace("\\", "_")
    }

    fun currentLogFile(context: Context): File = File(logDirectory(context), CURRENT_LOG_FILE_NAME)

    fun previousLogFile(context: Context): File = File(logDirectory(context), PREVIOUS_LOG_FILE_NAME)
}
