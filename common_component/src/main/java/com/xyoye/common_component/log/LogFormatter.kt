package com.xyoye.common_component.log

import android.util.Log
import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.log.model.LogTag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 将 LogEvent 序列化为结构化的单行文本，便于文件落盘与脚本解析。
 * 默认保留时间、级别、模块、线程、序号与上下文字段，并预留字段过滤扩展点。
 */
class LogFormatter(
    private val fieldFilter: FieldFilter = FieldFilter
) {
    private val utcDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * 用于落盘的完整格式。
     */
    fun format(event: LogEvent): String {
        val contextToUse = fieldFilter.filterContext(event.level, event.module, event.context)
        val threadPart = if (fieldFilter.includeThread(event.level, event.module)) {
            " thread=${sanitize(event.threadName)}"
        } else {
            ""
        }
        val seqPart = if (event.sequenceId > 0) " seq=${event.sequenceId}" else ""
        val tagPart = event.tag?.let { " tag=${sanitizeTag(it)}" } ?: ""
        val contextPart = if (contextToUse.isNotEmpty()) {
            val sorted = contextToUse.toSortedMap()
            val rendered = sorted.entries.joinToString(",") { (k, v) ->
                "${sanitize(k)}=${sanitize(v)}"
            }
            " context={$rendered}"
        } else {
            ""
        }
        val throwablePart = if (fieldFilter.includeThrowable(event.level, event.module) && event.throwable != null) {
            val stack = Log.getStackTraceString(event.throwable)
            " throwable=${sanitize(stack)}"
        } else {
            ""
        }

        val timeText = utcDateFormat.format(Date(event.timestamp))
        val header = "time=$timeText level=${event.level} module=${event.module.code}"
        val messagePart = " message=\"${sanitize(event.message)}\""
        return header + tagPart + threadPart + seqPart + contextPart + throwablePart + messagePart
    }

    /**
     * logcat 输出时的精简格式，保留核心信息。
     */
    fun formatForLogcat(event: LogEvent): String {
        val contextToUse = fieldFilter.filterContext(event.level, event.module, event.context)
        val contextPart = if (contextToUse.isNotEmpty()) {
            val rendered = contextToUse.toSortedMap().entries.joinToString(",") { (k, v) ->
                "${sanitize(k)}=${sanitize(v)}"
            }
            " {$rendered}"
        } else {
            ""
        }
        val tagPart = event.tag?.let { " [${sanitizeTag(it)}]" } ?: ""
        val seqPart = if (event.sequenceId > 0) " #${event.sequenceId}" else ""
        return "${event.level}/${event.module.code}$tagPart$seqPart: ${sanitize(event.message)}$contextPart"
    }

    private fun sanitize(raw: String): String {
        return raw.replace('\n', ' ').replace('\r', ' ').trim()
    }

    private fun sanitizeTag(tag: LogTag): String = "${tag.module.code}:${tag.value}"

    /**
     * 可根据级别 / 模块裁剪上下文字段或控制额外信息输出。
     */
    interface LogFieldFilter {
        fun filterContext(level: LogLevel, module: LogModule, context: Map<String, String>): Map<String, String>
        fun includeThread(level: LogLevel, module: LogModule): Boolean
        fun includeThrowable(level: LogLevel, module: LogModule): Boolean
    }

    object FieldFilter : LogFieldFilter {
        override fun filterContext(level: LogLevel, module: LogModule, context: Map<String, String>): Map<String, String> {
            return context
        }

        override fun includeThread(level: LogLevel, module: LogModule): Boolean = true

        override fun includeThrowable(level: LogLevel, module: LogModule): Boolean = true
    }
}
