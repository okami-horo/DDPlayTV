package com.xyoye.common_component.log.model

/**
 * 单条日志事件，包含基础元数据与结构化上下文。
 */
data class LogEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val module: LogModule,
    val tag: LogTag? = null,
    val message: String,
    val context: Map<String, String> = emptyMap(),
    val throwable: Throwable? = null,
    val threadName: String = Thread.currentThread().name,
    val sequenceId: Long = 0L
) {
    init {
        val trimmedMessage = message.trim()
        require(trimmedMessage.isNotEmpty()) { "LogEvent message must not be empty" }
        require(trimmedMessage.length <= MAX_MESSAGE_LENGTH) { "LogEvent message too long" }
        require(timestamp > 0) { "LogEvent timestamp must be positive" }

        context.forEach { (k, v) ->
            require(k.isNotBlank()) { "LogEvent context key must not be blank" }
            require(k.length <= MAX_CONTEXT_KEY_LENGTH) { "Context key too long: $k" }
            require(v.length <= MAX_CONTEXT_VALUE_LENGTH) { "Context value too long for key: $k" }
        }
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 2048
        const val MAX_CONTEXT_KEY_LENGTH = 32
        const val MAX_CONTEXT_VALUE_LENGTH = 256
    }
}
