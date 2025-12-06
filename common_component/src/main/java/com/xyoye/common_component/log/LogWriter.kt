package com.xyoye.common_component.log

import android.util.Log
import com.xyoye.common_component.log.model.DebugToggleState
import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.LogRuntimeState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 单线程写入执行器：根据策略决定 logcat / 本地文件输出。
 */
class LogWriter(
    context: android.content.Context,
    private val formatter: LogFormatter = LogFormatter(),
    private val fileManager: LogFileManager = LogFileManager(context),
    private val sampler: LogSampler = LogSampler(),
    private val onFileError: (Throwable) -> Unit = { error ->
        Log.e(LOG_TAG, "write log file failed", error)
    }
) {
    private val stateRef = AtomicReference(
        LogRuntimeState(
            activePolicy = LogPolicy.defaultReleasePolicy()
        )
    )
    private var consecutiveFileErrors = 0
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LogWriter").apply { isDaemon = true }
    }

    fun updateRuntimeState(state: LogRuntimeState) {
        stateRef.set(state)
    }

    fun submit(event: LogEvent) {
        executor.execute { writeInternal(event) }
    }

    private fun writeInternal(event: LogEvent) {
        val runtime = stateRef.get()
        val policy = runtime.activePolicy
        if (!shouldEmit(event.level, policy.defaultLevel)) {
            return
        }
        if (!sampler.shouldAllow(event, policy)) {
            return
        }
        writeToLogcat(event)
        if (shouldWriteFile(runtime)) {
            runCatching {
                fileManager.prepare()
                fileManager.appendLine(formatter.format(event))
                consecutiveFileErrors = 0
            }.onFailure { error -> handleFileError(error) }
        }
    }

    private fun shouldEmit(level: LogLevel, threshold: LogLevel): Boolean {
        return levelPriority(level) >= levelPriority(threshold)
    }

    private fun shouldWriteFile(runtime: LogRuntimeState): Boolean {
        if (!runtime.activePolicy.enableDebugFile) return false
        if (runtime.debugToggleState == DebugToggleState.DISABLED_DUE_TO_ERROR) return false
        return runtime.debugSessionEnabled
    }

    private fun writeToLogcat(event: LogEvent) {
        val tag = buildLogcatTag(event)
        val content = formatter.formatForLogcat(event)
        when (event.level) {
            LogLevel.DEBUG -> Log.d(tag, content, event.throwable)
            LogLevel.INFO -> Log.i(tag, content, event.throwable)
            LogLevel.WARN -> Log.w(tag, content, event.throwable)
            LogLevel.ERROR -> Log.e(tag, content, event.throwable)
        }
    }

    private fun buildLogcatTag(event: LogEvent): String {
        val base = "DDLog-${event.module.code}"
        val tail = event.tag?.value?.takeIf { it.isNotBlank() }
        val tag = if (tail.isNullOrBlank()) base else "$base-${tail}"
        // Android logcat tag 上限 23 字符，超长时截断
        return if (tag.length <= 23) tag else tag.take(23)
    }

    private fun levelPriority(level: LogLevel): Int {
        return when (level) {
            LogLevel.DEBUG -> 0
            LogLevel.INFO -> 1
            LogLevel.WARN -> 2
            LogLevel.ERROR -> 3
        }
    }

    private fun handleFileError(error: Throwable) {
        consecutiveFileErrors += 1
        if (consecutiveFileErrors == MAX_CONSECUTIVE_ERRORS_BEFORE_DISABLE) {
            val current = stateRef.get()
            val disabled = current.copy(
                debugToggleState = DebugToggleState.DISABLED_DUE_TO_ERROR,
                debugSessionEnabled = false,
                lastPolicyUpdateTime = System.currentTimeMillis()
            )
            stateRef.set(disabled)
        }
        onFileError(error)
    }

    internal fun currentStateForTest(): LogRuntimeState = stateRef.get()

    companion object {
        private const val LOG_TAG = "LogWriter"
        private const val MAX_CONSECUTIVE_ERRORS_BEFORE_DISABLE = 1
    }
}
