package com.xyoye.common_component.log

import android.content.Context
import com.xyoye.common_component.utils.PathHelper
import java.io.File

/**
 * 集中管理日志相关的路径与文件名，避免字符串散落。
 *
 * 默认将日志写入「应用外部缓存根目录」下的 logs 子目录：
 * - 例如：/sdcard/Android/data/<package-name>/files/logs/
 * 如外部目录不可用，则回退到内部 files/logs。
 */
object LogPaths {
    const val LOG_DIR_NAME = "logs"
    const val CURRENT_LOG_FILE_NAME = "debug.log"
    const val PREVIOUS_LOG_FILE_NAME = "debug_old.log"

    /**
     * 返回日志目录：
     * - 优先使用 PathHelper.getCachePath() 作为根目录（外部 files 目录）
     * - 若该路径不可用，则回退到 context.filesDir/logs
     */
    fun logDirectory(context: Context): File {
        val externalRoot = runCatching { PathHelper.getCachePath() }.getOrNull()
        val baseDir = externalRoot?.takeIf { it.isNotBlank() }?.let { File(it) }
        return if (baseDir != null) {
            File(baseDir, LOG_DIR_NAME)
        } else {
            File(context.filesDir, LOG_DIR_NAME)
        }
    }

    fun currentLogFile(context: Context): File =
        File(logDirectory(context), CURRENT_LOG_FILE_NAME)

    fun previousLogFile(context: Context): File =
        File(logDirectory(context), PREVIOUS_LOG_FILE_NAME)
}
