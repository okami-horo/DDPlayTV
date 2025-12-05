package com.xyoye.common_component.log

import android.content.Context
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * 兼容层：保留旧 API，但内部委托给新的日志系统。
 */
@Deprecated("请直接使用 LogSystem/LogFacade")
object AppLogger {

    private val initialized = AtomicBoolean(false)
    private var fileManager: LogFileManager? = null

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        fileManager = LogFileManager(context.applicationContext).also { it.prepare() }
    }

    fun log(level: Level, tag: String?, message: String, throwable: Throwable?) {
        val module = resolveModule(tag)
        val mappedLevel = when (level) {
            Level.WARNING -> LogLevel.WARN
            Level.SEVERE -> LogLevel.ERROR
            Level.INFO -> LogLevel.INFO
            else -> LogLevel.INFO
        }
        com.xyoye.common_component.log.LogFacade.log(
            level = mappedLevel,
            module = module,
            tag = tag,
            message = message,
            context = emptyMap(),
            throwable = throwable
        )
    }

    fun listLocalLogs(): List<File> {
        val manager = fileManager ?: return emptyList()
        return manager.listLogFiles().map { File(it.path) }
    }

    private fun resolveModule(tag: String?): LogModule {
        val caller = Throwable().stackTrace
            .firstOrNull { it.className.startsWith("com.xyoye") }
            ?.className
        return LogModule.fromPackage(caller ?: tag)
    }
}
