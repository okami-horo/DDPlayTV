package com.xyoye.common_component.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * 应用日志写入与本地文件管理
 */
object AppLogger {

    private const val LOG_FOLDER = "logs"
    private const val LOG_FILE_PREFIX = "app"
    private const val MAX_BACKUP_COUNT = 5
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024 // 2MB

    private val initialized = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppLogger").apply { isDaemon = true }
    }

    private fun internalLog(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        if (!initialized.get()) {
            return
        }
        executor.execute {
            try {
                val now = Date()
                val logLine = buildLogLine(level, tag, message, throwable, now)
                val file = resolveCurrentFile(now)
                FileWriter(file, true).use { writer ->
                    writer.appendLine(logLine)
                    throwable?.let { writer.appendLine(Log.getStackTraceString(it)) }
                }
                rotateIfNeed(file)
            } catch (e: Exception) {
                Log.e("AppLogger", "write log failed", e)
            }
        }
    }

    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val startupTimeFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US)

    private lateinit var logDir: File
    private var currentLogFile: File? = null
    private var startupTime: Long = 0L

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) {
            return
        }
        logDir = resolveLogDirectory(context.applicationContext)
        startupTime = System.currentTimeMillis()
        currentLogFile = resolveLogFile(startupTime)
        internalLog(Level.INFO, "APP-Logger", "initialized dir=${logDir.absolutePath}")
    }

    private fun resolveLogDirectory(context: Context): File {
        context.getExternalFilesDir(null)?.let { externalRoot ->
            val externalDir = File(externalRoot, LOG_FOLDER)
            if (externalDir.exists() || externalDir.mkdirs()) {
                return externalDir
            }
        }

        val internalDir = File(context.filesDir, LOG_FOLDER)
        if (!internalDir.exists()) {
            internalDir.mkdirs()
        }
        return internalDir
    }

    fun log(level: Level, tag: String?, message: String, throwable: Throwable?) {
        if (!initialized.get()) {
            return
        }
        enqueueLog(level, tag, message, throwable)
    }

    private fun enqueueLog(
        level: Level,
        tag: String?,
        message: String,
        throwable: Throwable?
    ) {
        executor.execute {
            try {
                val now = Date()
                val logLine = buildLogLine(level, tag, message, throwable, now)
                val file = resolveCurrentFile(now)
                FileWriter(file, true).use { writer ->
                    writer.appendLine(logLine)
                    throwable?.let { writer.appendLine(Log.getStackTraceString(it)) }
                }
                rotateIfNeed(file)
            } catch (e: Exception) {
                Log.e("AppLogger", "write log failed", e)
            }
        }
    }

    private fun buildLogLine(
        level: Level,
        tag: String?,
        message: String,
        throwable: Throwable?,
        date: Date
    ): String {
        val time = logDateFormat.format(date)
        val levelText = level.name
        val tagText = if (tag.isNullOrBlank()) "-" else tag
        val throwableMsg = throwable?.message?.let { " | ${it}" } ?: ""
        return "$time [$levelText][$tagText] $message$throwableMsg"
    }

    private fun resolveCurrentFile(date: Date): File {
        if (currentLogFile == null) {
            currentLogFile = resolveLogFile(startupTime)
        }
        return currentLogFile!!
    }

    private fun resolveLogFile(startMillis: Long): File {
        val formatted = startupTimeFormat.format(Date(startMillis))
        val primaryFile = File(logDir, "$LOG_FILE_PREFIX-$formatted-$startMillis.log")
        if (!primaryFile.exists()) {
            primaryFile.createNewFile()
        }
        return primaryFile
    }

    private fun rotateIfNeed(file: File) {
        if (file.length() < MAX_FILE_SIZE) {
            return
        }
        for (index in MAX_BACKUP_COUNT downTo 1) {
            val backup = File(logDir, "${file.name}.$index")
            if (!backup.exists()) {
                continue
            }
            if (index == MAX_BACKUP_COUNT) {
                backup.delete()
            } else {
                val target = File(logDir, "${file.name}.${index + 1}")
                backup.renameTo(target)
            }
        }
        val backup = File(logDir, "${file.name}.1")
        file.renameTo(backup)
        file.createNewFile()
    }

    fun listLocalLogs(): List<File> {
        if (!initialized.get()) {
            return emptyList()
        }
        return logDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
    }
}
