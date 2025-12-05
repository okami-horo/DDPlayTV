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
 * - 文件格式突出时间、级别、模块、关键上下文字段，并在 DEBUG 级别下对噪声上下文做简单裁剪。
 * - logcat 格式保持紧凑，但保留关键字段与序号，便于串联问题链路。
 */
class LogFormatter(
    private val fieldFilter: LogFieldFilter = FieldFilter,
    private val maxDebugContextEntries: Int = MAX_DEBUG_CONTEXT_ENTRIES
) {
    private val utcDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun format(event: LogEvent): String {
        val contextInfo = prepareContext(event)
        val headerParts = mutableListOf<String>()
        headerParts += "time=${utcDateFormat.format(Date(event.timestamp))}"
        headerParts += "level=${event.level}"
        headerParts += "module=${event.module.code}"
        event.tag?.let { headerParts += "tag=${sanitizeTag(it)}" }
        if (fieldFilter.includeThread(event.level, event.module)) {
            headerParts += "thread=${sanitize(event.threadName, MAX_THREAD_NAME_LENGTH)}"
        }
        if (event.sequenceId > 0) headerParts += "seq=${event.sequenceId}"
        headerParts += renderHighlightForFile(contextInfo.highlight)
        if (contextInfo.droppedCount > 0) {
            headerParts += "ctx_dropped=${contextInfo.droppedCount}"
        }
        val contextPart = renderContextBlock(contextInfo.context)
        val throwablePart = renderThrowable(event)
        val messagePart = """msg="${sanitizeMessage(event.message)}""""
        return buildString {
            append(headerParts.filter { it.isNotBlank() }.joinToString(" "))
            if (contextPart.isNotEmpty()) append(" ").append(contextPart)
            if (throwablePart.isNotEmpty()) append(" ").append(throwablePart)
            append(" ").append(messagePart)
        }
    }

    fun formatForLogcat(event: LogEvent): String {
        val contextInfo = prepareContext(event)
        val highlightPart = renderHighlightForLogcat(contextInfo.highlight)
        val contextPart = renderInlineContext(contextInfo.context)
        val droppedPart = if (contextInfo.droppedCount > 0) " ctx_dropped=${contextInfo.droppedCount}" else ""
        val prefix = buildString {
            append("${event.level}/${event.module.code}")
            event.tag?.let { append(" [${sanitizeTag(it)}]") }
            if (event.sequenceId > 0) append(" #${event.sequenceId}")
            if (highlightPart.isNotEmpty()) {
                append(" ")
                append(highlightPart)
            }
        }
        return buildString {
            append(prefix)
            append(": ")
            append(sanitizeMessage(event.message))
            if (contextPart.isNotEmpty()) {
                append(" ")
                append(contextPart)
            }
            append(droppedPart)
        }
    }

    private fun prepareContext(event: LogEvent): ContextRender {
        val filtered = fieldFilter.filterContext(event.level, event.module, event.context)
        if (filtered.isEmpty()) return ContextRender()
        val highlight = linkedMapOf<String, String>()
        val remaining = linkedMapOf<String, String>()
        filtered.forEach { (rawKey, rawValue) ->
            val normalized = rawKey.lowercase(Locale.US)
            val canonicalKey = HIGHLIGHT_KEYS[normalized]
            val sanitizedValue = sanitize(rawValue, MAX_CONTEXT_VALUE_LENGTH)
            if (canonicalKey != null) {
                highlight[canonicalKey] = sanitizedValue
            } else {
                remaining[sanitize(rawKey, MAX_KEY_LENGTH)] = sanitizedValue
            }
        }
        val sortedRemaining = remaining.toSortedMap()
        if (event.level != LogLevel.DEBUG || sortedRemaining.size <= maxDebugContextEntries) {
            return ContextRender(
                highlight = highlight.toSortedMap(),
                context = sortedRemaining,
                droppedCount = 0
            )
        }
        val kept = sortedRemaining.entries.take(maxDebugContextEntries).associate { it.toPair() }
        val dropped = sortedRemaining.size - kept.size
        return ContextRender(
            highlight = highlight.toSortedMap(),
            context = kept,
            droppedCount = dropped
        )
    }

    private fun renderHighlightForFile(highlight: Map<String, String>): List<String> {
        if (highlight.isEmpty()) return emptyList()
        return highlight.entries.sortedBy { it.key }.map { (k, v) -> "ctx_${k}=${v}" }
    }

    private fun renderHighlightForLogcat(highlight: Map<String, String>): String {
        if (highlight.isEmpty()) return ""
        return highlight.entries.sortedBy { it.key }.joinToString(" ") { (k, v) -> "$k=$v" }
    }

    private fun renderContextBlock(context: Map<String, String>): String {
        if (context.isEmpty()) return ""
        val rendered = context.entries.joinToString(",") { (k, v) -> "$k=$v" }
        return "context={$rendered}"
    }

    private fun renderInlineContext(context: Map<String, String>): String {
        if (context.isEmpty()) return ""
        val rendered = context.entries.joinToString(",") { (k, v) -> "$k=$v" }
        return "{$rendered}"
    }

    private fun renderThrowable(event: LogEvent): String {
        if (!fieldFilter.includeThrowable(event.level, event.module)) return ""
        val throwable = event.throwable ?: return ""
        val stack = Log.getStackTraceString(throwable)
        return "throwable=${sanitize(stack, MAX_THROWABLE_LENGTH)}"
    }

    private fun sanitizeMessage(raw: String): String = sanitize(raw, MAX_MESSAGE_LENGTH)

    private fun sanitizeTag(tag: LogTag): String = sanitize("${tag.module.code}:${tag.value}", MAX_TAG_LENGTH)

    private fun sanitize(raw: String, limit: Int): String {
        val cleaned = raw.replace('\n', ' ').replace('\r', ' ').trim()
        if (cleaned.length <= limit) return cleaned
        return cleaned.take(limit) + "..."
    }

    data class ContextRender(
        val highlight: Map<String, String> = emptyMap(),
        val context: Map<String, String> = emptyMap(),
        val droppedCount: Int = 0
    )

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

    companion object {
        private const val MAX_MESSAGE_LENGTH = 1024
        private const val MAX_CONTEXT_VALUE_LENGTH = 256
        private const val MAX_KEY_LENGTH = 64
        private const val MAX_THROWABLE_LENGTH = 1200
        private const val MAX_TAG_LENGTH = 48
        private const val MAX_THREAD_NAME_LENGTH = 48
        private const val MAX_DEBUG_CONTEXT_ENTRIES = 6

        private val HIGHLIGHT_KEYS = mapOf(
            "scene" to "scene",
            "errorcode" to "errorCode",
            "sessionid" to "sessionId",
            "requestid" to "requestId",
            "action" to "action",
            "source" to "source"
        )
    }
}
