package com.xyoye.common_component.utils

import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * 错误上报工具类
 * 统一处理异常上报，遵循 CLAUDE.md 中的 Bugly 上报约定
 *
 * Created by Claude Code on 2025-08-30.
 */
object ErrorReportHelper {

    /**
     * 上报捕获的异常
     * 使用 CrashReport.postCatchedException() 方法，符合约定
     * 
     * @param throwable 捕获的异常
     * @param tag 错误标签，用于分类
     * @param extraInfo 额外信息
     */
    fun postCatchedException(throwable: Throwable, tag: String = "", extraInfo: String = "") {
        try {
            // 过滤掉 CancellationException，这是协程正常取消的标志，不应当作错误上报
            if (throwable is CancellationException) {
                // 在调试模式下仍然打印信息，便于本地调试
                if (com.xyoye.common_component.BuildConfig.DEBUG) {
                    println("[$tag] CancellationException ignored: $extraInfo")
                }
                return
            }
            
            // 遵循 CLAUDE.md 约定：统一使用 CrashReport.postCatchedException()
            CrashReport.postCatchedException(throwable)
            
            // 在调试模式下仍然打印堆栈信息，便于本地调试
            if (com.xyoye.common_component.BuildConfig.DEBUG) {
                println("[$tag] Exception reported: $extraInfo")
                throwable.printStackTrace()
            }
        } catch (e: Exception) {
            // 防止错误上报本身出现异常
            e.printStackTrace()
        }
    }

    /**
     * 上报自定义异常
     * 使用 CrashReport.postCatchedException() 方法，符合约定
     * 
     * @param message 错误信息
     * @param tag 错误标签
     * @param cause 原因异常（可选）
     */
    fun postException(message: String, tag: String = "", cause: Throwable? = null) {
        try {
            val exception = if (cause != null) {
                RuntimeException("[$tag] $message", cause)
            } else {
                RuntimeException("[$tag] $message")
            }
            
            // 遵循 CLAUDE.md 约定：统一使用 CrashReport.postCatchedException()
            CrashReport.postCatchedException(exception)
            
            // 在调试模式下打印信息
            if (com.xyoye.common_component.BuildConfig.DEBUG) {
                println("[$tag] Custom exception reported: $message")
                exception.printStackTrace()
            }
        } catch (e: Exception) {
            // 防止错误上报本身出现异常
            e.printStackTrace()
        }
    }

    /**
     * 带有完整上下文信息的异常上报
     * 
     * @param throwable 异常对象
     * @param className 发生异常的类名
     * @param methodName 发生异常的方法名
     * @param extraInfo 额外的上下文信息
     */
    fun postCatchedExceptionWithContext(
        throwable: Throwable,
        className: String,
        methodName: String,
        extraInfo: String = ""
    ) {
        // 过滤掉 CancellationException，这是协程正常取消的标志，不应当作错误上报
        if (throwable is CancellationException) {
            if (com.xyoye.common_component.BuildConfig.DEBUG) {
                println("[$className.$methodName] CancellationException ignored: $extraInfo")
            }
            return
        }
        
        val contextInfo = "Class: $className, Method: $methodName"
        val fullInfo = if (extraInfo.isNotEmpty()) {
            "$contextInfo, Extra: $extraInfo"
        } else {
            contextInfo
        }
        
        postCatchedException(throwable, "Context", fullInfo)
    }

    /**
     * 上报HTTP 403 Forbidden错误，包含详细的认证诊断信息
     *
     * @param httpException HTTP异常
     * @param className 发生异常的类名
     * @param methodName 发生异常的方法名
     * @param extraInfo 额外的上下文信息
     */
    fun post403Exception(
        httpException: HttpException,
        className: String,
        methodName: String,
        extraInfo: String = ""
    ) {
        val authDiagnosis = AuthenticationHelper.getAuthenticationDiagnosis()
        val fullExtraInfo = buildString {
            append(extraInfo)
            if (extraInfo.isNotEmpty()) {
                append("\n\n")
            }
            append(authDiagnosis)
        }

        postCatchedExceptionWithContext(
            httpException,
            className,
            methodName,
            fullExtraInfo
        )
    }
}