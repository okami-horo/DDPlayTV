package com.xyoye.common_component.log

import android.content.Context
import com.xyoye.common_component.config.DevelopLogConfigDefaults
import com.xyoye.common_component.log.model.LogFileMeta
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 管理内部存储下的 debug.log / debug_old.log 双文件。
 * - 冷启动时将上一会话的 debug.log 合并到 debug_old.log（按上限裁剪）
 * - 仅在需要写入时创建目录和文件
 */
open class LogFileManager(
    private val context: Context,
    private val fileSizeLimitBytes: Long = DevelopLogConfigDefaults.DEFAULT_LOG_FILE_SIZE_LIMIT_BYTES
) {
    private val prepared = AtomicBoolean(false)
    private val ioLock = Any()

    fun prepare() {
        if (prepared.get()) return
        synchronized(ioLock) {
            if (prepared.get()) return
            ensureDirectory()
            mergeCurrentIntoOld()
            prepared.set(true)
        }
    }

    fun appendLine(line: String) {
        prepare()
        synchronized(ioLock) {
            val current = currentLogFile()
            FileWriter(current, true).use { writer ->
                writer.appendLine(line)
            }
            rolloverIfNeeded(current)
        }
    }

    fun listLogFiles(): List<LogFileMeta> {
        prepare()
        val current = currentLogFile()
        val previous = previousLogFile()
        val files = listOf(current, previous)
        return files.filter { it.exists() }.map { file ->
            LogFileMeta(
                fileName = file.name,
                path = file.absolutePath,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                readable = file.canRead()
            )
        }
    }

    fun logDirectory(): File = LogPaths.logDirectory(context)

    fun currentLogFile(): File = LogPaths.currentLogFile(context)

    fun previousLogFile(): File = LogPaths.previousLogFile(context)

    private fun ensureDirectory() {
        val dir = logDirectory()
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }

    private fun mergeCurrentIntoOld() {
        val current = currentLogFile()
        val previous = previousLogFile()
        if (current.exists() && current.length() > 0) {
            appendFileWithClamp(current, previous)
            current.writeText("")
        } else if (!current.exists()) {
            current.createNewFile()
        }
        if (!previous.exists()) {
            previous.createNewFile()
        }
    }

    private fun rolloverIfNeeded(current: File) {
        if (current.length() <= fileSizeLimitBytes) return
        val previous = previousLogFile()
        appendFileWithClamp(current, previous)
        current.writeText("")
    }

    private fun appendFileWithClamp(source: File, target: File) {
        if (source.length() == 0L) return
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            target.createNewFile()
        }
        target.appendBytes(source.readBytes())
        clampFile(target)
    }

    private fun clampFile(file: File) {
        val limit = fileSizeLimitBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (file.length() <= fileSizeLimitBytes) {
            return
        }
        val bytes = file.readBytes()
        if (bytes.size <= limit) {
            return
        }
        val sliceStart = (bytes.size - limit).coerceAtLeast(0)
        file.writeBytes(bytes.copyOfRange(sliceStart, bytes.size))
    }
}
