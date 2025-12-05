package com.xyoye.common_component.log

import android.content.Context
import java.io.File

/**
 * 集中管理日志相关的路径与文件名，避免字符串散落。
 */
object LogPaths {
    const val LOG_DIR_NAME = "logs"
    const val CURRENT_LOG_FILE_NAME = "debug.log"
    const val PREVIOUS_LOG_FILE_NAME = "debug_old.log"

    fun logDirectory(context: Context): File = File(context.filesDir, LOG_DIR_NAME)

    fun currentLogFile(context: Context): File =
        File(logDirectory(context), CURRENT_LOG_FILE_NAME)

    fun previousLogFile(context: Context): File =
        File(logDirectory(context), PREVIOUS_LOG_FILE_NAME)
}
