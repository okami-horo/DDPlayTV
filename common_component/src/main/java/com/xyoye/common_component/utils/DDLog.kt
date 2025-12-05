package com.xyoye.common_component.utils

import com.xyoye.common_component.config.DevelopConfig
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule

/**
 * Created by xyoye on 2020/12/29.
 */

object DDLog {
    @Volatile
    var enable: Boolean = loadDefaultState()
        private set

    private fun loadDefaultState(): Boolean {
        return runCatching { DevelopConfig.isDdLogEnable() }.getOrDefault(false)
    }

    fun refreshEnableFromConfig() {
        enable = loadDefaultState()
    }

    fun setEnable(enable: Boolean) {
        this.enable = enable
    }

    fun i(message: String, throwable: Throwable? = null) = i(null, message, throwable)

    fun i(tag: String?, message: String, throwable: Throwable? = null) {
        log(tag, message, LogLevel.INFO, throwable)
    }

    fun w(message: String, throwable: Throwable? = null) = w(null, message, throwable)

    fun w(tag: String?, message: String, throwable: Throwable? = null) {
        log(tag, message, LogLevel.WARN, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) = e(null, message, throwable)

    fun e(tag: String?, message: String, throwable: Throwable? = null) {
        log(tag, message, LogLevel.ERROR, throwable)
    }

    fun d(message: String, throwable: Throwable? = null) = d(null, message, throwable)

    fun d(tag: String?, message: String, throwable: Throwable? = null) {
        log(tag, message, LogLevel.DEBUG, throwable)
    }

    private fun log(tag: String?, message: String, level: LogLevel, throwable: Throwable? = null) {
        if (!enable) return
        val module = resolveModuleFromCaller()
        val tagText = tag?.takeIf { it.isNotBlank() }
        LogFacade.log(
            level = level,
            module = module,
            tag = tagText,
            message = message,
            context = emptyMap(),
            throwable = throwable
        )
    }

    private fun resolveModuleFromCaller(): LogModule {
        val callerPackage = Throwable().stackTrace
            .firstOrNull { it.className.startsWith("com.xyoye") }
            ?.className
        return LogModule.fromPackage(callerPackage)
    }
}
