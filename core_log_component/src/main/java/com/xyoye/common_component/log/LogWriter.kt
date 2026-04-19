package com.xyoye.common_component.log

import android.util.Log
import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.LogRuntimeState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 单线程写入执行器：根据策略决定 logcat 输出，并可选写入 HTTP 日志通道。
 */
class LogWriter(
    private val formatter: LogFormatter = LogFormatter(),
    private val sampler: LogSampler = LogSampler(),
    private val httpLogSink: (LogEvent) -> Unit = {},
) {
    private val stateRef =
        AtomicReference(
            LogRuntimeState(
                activePolicy = LogPolicy.defaultReleasePolicy(),
            ),
        )
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
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
        runCatching { httpLogSink(event) }
    }

    private fun shouldEmit(
        level: LogLevel,
        threshold: LogLevel
    ): Boolean = level.isAtLeast(threshold)

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
        val tag = if (tail.isNullOrBlank()) base else "$base-$tail"
        // Android logcat tag 上限 23 字符，超长时截断
        return if (tag.length <= 23) tag else tag.take(23)
    }

    internal fun currentStateForTest(): LogRuntimeState = stateRef.get()
}
