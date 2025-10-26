package com.xyoye.common_component.log

import android.content.Context
import android.util.Log
import com.xyoye.common_component.config.DevelopConfig
import com.xyoye.common_component.network.helper.UnsafeOkHttpClient
import com.xyoye.common_component.storage.impl.WebDavUploader
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.SupervisorScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level

/**
 * 应用日志写入与 WebDAV 上传管理
 */
object AppLogger {

    private const val LOG_FOLDER = "logs"
    private const val LOG_FILE_PREFIX = "app"
    private const val MAX_BACKUP_COUNT = 5
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024 // 2MB
    private const val MIN_UPLOAD_INTERVAL = 30 * 60 * 1000L // 30 分钟
    private const val UPLOAD_SIZE_THRESHOLD = 512 * 1024 // 512KB

    private val initialized = AtomicBoolean(false)
    private val uploading = AtomicBoolean(false)
    private val lastUploadCheck = AtomicLong(0L)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppLogger").apply { isDaemon = true }
    }

    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private lateinit var appContext: Context
    private lateinit var logDir: File
    private var currentLogFile: File? = null
    private var currentDay: String = ""

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) {
            return
        }
        appContext = context.applicationContext
        logDir = resolveLogDirectory(appContext)
        currentDay = dayFormat.format(Date())
        currentLogFile = resolveLogFile(currentDay)
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
                maybeUpload(file)
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
        val day = dayFormat.format(date)
        if (day != currentDay || currentLogFile == null) {
            currentDay = day
            currentLogFile = resolveLogFile(day)
        }
        return currentLogFile!!
    }

    private fun resolveLogFile(day: String): File {
        val primaryFile = File(logDir, "$LOG_FILE_PREFIX-$day.log")
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

    private fun maybeUpload(file: File) {
        if (!DevelopConfig.isLogUploadEnable()) {
            return
        }
        if (file.length() < UPLOAD_SIZE_THRESHOLD) {
            return
        }
        val now = System.currentTimeMillis()
        val lastCheck = lastUploadCheck.get()
        if (now - lastCheck < MIN_UPLOAD_INTERVAL) {
            return
        }
        if (!lastUploadCheck.compareAndSet(lastCheck, now)) {
            return
        }
        triggerUpload()
    }

    fun triggerUpload() {
        if (uploading.get()) {
            return
        }
        uploading.set(true)
        SupervisorScope.IO.launch {
            try {
                uploadInternal()
            } catch (e: Exception) {
                Log.e("AppLogger", "upload failed", e)
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "AppLogger",
                    "uploadInternal",
                    "上传日志至 WebDAV 失败"
                )
            } finally {
                uploading.set(false)
            }
        }
    }

    private fun uploadInternal() {
        val enabled = DevelopConfig.isLogUploadEnable()
        if (!enabled) {
            return
        }
        val baseUrl = DevelopConfig.getLogUploadUrl()?.trim().orEmpty()
        if (baseUrl.isEmpty()) {
            return
        }
        val username = DevelopConfig.getLogUploadUsername().orEmpty()
        val password = DevelopConfig.getLogUploadPassword().orEmpty()
        val remotePath = DevelopConfig.getLogUploadRemotePath().orEmpty()
        val files = logDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            return
        }
        val uploader = WebDavUploader(UnsafeOkHttpClient.client, baseUrl, username, password)
        ensureRemoteDirectory(uploader, remotePath)
        for (logFile in files) {
            if (!logFile.isFile) {
                continue
            }
            uploadSingleFile(uploader, remotePath, logFile)
        }
        DevelopConfig.putLogUploadLastTime(System.currentTimeMillis())
    }

    private fun ensureRemoteDirectory(
        uploader: WebDavUploader,
        remotePath: String
    ) {
        val segments = remotePath.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (segment in segments) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            uploader.ensureDirectory(current)
        }
    }

    private fun uploadSingleFile(
        uploader: WebDavUploader,
        remotePath: String,
        file: File
    ) {
        val targetPath = buildRemotePath(remotePath, file.name)
        uploader.uploadFile(targetPath, file)
    }

    private fun buildRemotePath(remotePath: String, fileName: String): String {
        val sanitizedPath = remotePath.trim('/').takeIf { it.isNotEmpty() }
        return when (sanitizedPath) {
            null -> fileName
            else -> "$sanitizedPath/$fileName"
        }
    }

    fun listLocalLogs(): List<File> {
        if (!initialized.get()) {
            return emptyList()
        }
        return logDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
    }
}
