package com.xyoye.common_component.log

import android.content.Context
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import java.io.File
import java.util.logging.Level

/**
 * 兼容层：保留旧 API，但内部委托给新的日志系统。
 */
@Deprecated("请直接使用 LogSystem/LogFacade")
object AppLogger {

    fun init(context: Context) {
        if (!LogSystem.isInitialized()) {
            LogSystem.init(context.applicationContext)
        }
    }

    fun log(level: Level, tag: String?, message: String, throwable: Throwable?) {
        val module = resolveModule(tag)
        val mappedLevel = when (level) {
            Level.WARNING -> LogLevel.WARN
            Level.SEVERE -> LogLevel.ERROR
            Level.INFO -> LogLevel.INFO
            else -> LogLevel.INFO
        }
        LogFacade.log(
            level = mappedLevel,
            module = module,
            tag = tag,
            message = message,
            context = emptyMap(),
            throwable = throwable
        )
    }

    @Deprecated("请改用 LogFileManager(context).listLogFiles() 或 LogSystem API")
    fun listLocalLogs(context: Context): List<File> {
        return LogFileManager(context.applicationContext).listLogFiles().map { File(it.path) }
    }

    private fun resolveModule(tag: String?): LogModule {
        val caller = Throwable().stackTrace
            .firstOrNull { it.className.startsWith("com.xyoye") }
            ?.className
        return LogModule.fromPackage(caller ?: tag)
    }
}
