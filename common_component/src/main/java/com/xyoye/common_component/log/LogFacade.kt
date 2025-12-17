package com.xyoye.common_component.log

import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.log.model.LogTag

/**
 * 对业务代码暴露的统一日志门面。
 */
object LogFacade {
    fun d(
        module: LogModule,
        tag: String? = null,
        message: String,
        context: Map<String, String> = emptyMap(),
        throwable: Throwable? = null
    ) = log(LogLevel.DEBUG, module, tag, message, context, throwable)

    fun i(
        module: LogModule,
        tag: String? = null,
        message: String,
        context: Map<String, String> = emptyMap(),
        throwable: Throwable? = null
    ) = log(LogLevel.INFO, module, tag, message, context, throwable)

    fun w(
        module: LogModule,
        tag: String? = null,
        message: String,
        context: Map<String, String> = emptyMap(),
        throwable: Throwable? = null
    ) = log(LogLevel.WARN, module, tag, message, context, throwable)

    fun e(
        module: LogModule,
        tag: String? = null,
        message: String,
        context: Map<String, String> = emptyMap(),
        throwable: Throwable? = null
    ) = log(LogLevel.ERROR, module, tag, message, context, throwable)

    fun log(
        level: LogLevel,
        module: LogModule,
        tag: String? = null,
        message: String,
        context: Map<String, String> = emptyMap(),
        throwable: Throwable? = null
    ) {
        val logTag = tag?.takeIf { it.isNotBlank() }?.let { LogTag(module, it) }
        val event =
            LogEvent(
                level = level,
                module = module,
                tag = logTag,
                message = message,
                context = context,
                throwable = throwable,
            )
        LogSystem.log(event)
    }
}
