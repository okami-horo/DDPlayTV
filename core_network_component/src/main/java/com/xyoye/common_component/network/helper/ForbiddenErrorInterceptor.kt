package com.xyoye.common_component.network.helper

import com.xyoye.common_component.config.UserConfig
import com.xyoye.common_component.utils.AuthenticationHelper
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.SecurityHelper
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.HttpException

/**
 * 403错误诊断和恢复拦截器
 * 用于诊断HTTP 403 Forbidden错误的根本原因，并提供适当的错误处理
 *
 * Created by Claude Code on 2025-09-22.
 */
class ForbiddenErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // 检查是否为403错误
        if (response.code == 403) {
            val url = request.url
            val path = url.encodedPath
            val headers = request.headers

            // 诊断403错误的原因
            val diagnosis = diagnose403Error(path, headers)

            // 上报详细的错误信息
            report403Error(path, headers, diagnosis)

            // 尝试恢复策略
            return tryRecovery(chain, request, diagnosis)
        }

        return response
    }

    /**
     * 诊断403错误的可能原因
     */
    private fun diagnose403Error(
        path: String,
        headers: okhttp3.Headers
    ): String {
        val diagnosis = StringBuilder()

        // 检查用户认证状态
        val userToken = UserConfig.getUserToken()
        val isLoggedIn = UserConfig.isUserLoggedIn()

        diagnosis.append("=== 403错误诊断 ===\n")
        diagnosis.append("请求路径: $path\n")
        diagnosis.append("用户登录状态: $isLoggedIn\n")
        diagnosis.append("用户Token: ${userToken ?: "空"}\n")

        // 检查请求头中的认证信息
        val authHeader = headers["Authorization"]
        diagnosis.append("Authorization头: ${authHeader ?: "空"}\n")

        // 检查应用认证状态
        val securityHelper = SecurityHelper.getInstance()
        val isOfficial = securityHelper.isOfficialApplication()
        diagnosis.append("官方应用认证: $isOfficial\n")

        // 检查签名相关信息
        val signatureHeaders =
            listOf(
                "X-Signature",
                "X-Timestamp",
                "X-Nonce",
                "X-App-Version",
                "X-Platform",
            )

        diagnosis.append("签名相关头信息:\n")
        signatureHeaders.forEach { headerName ->
            val value = headers[headerName]
            diagnosis.append("  $headerName: ${value ?: "空"}\n")
        }

        // 检查应用ID
        try {
            val appId = securityHelper.appId
            diagnosis.append("应用ID: ${appId.ifEmpty { "空" }}\n")
        } catch (e: Exception) {
            diagnosis.append("应用ID: 获取失败 (${e.message})\n")
        }

        // 分析可能的原因
        diagnosis.append("\n=== 可能的原因分析 ===\n")

        when {
            !isLoggedIn -> diagnosis.append("❌ 用户未登录\n")
            userToken.isNullOrEmpty() -> diagnosis.append("❌ 用户Token为空\n")
            authHeader.isNullOrEmpty() -> diagnosis.append("❌ Authorization请求头缺失\n")
            !authHeader.startsWith("Bearer ") -> diagnosis.append("❌ Authorization格式不正确\n")
            !isOfficial -> diagnosis.append("⚠️ 非官方应用认证（fork项目正常行为）\n")
            else -> diagnosis.append("❓ 服务器端权限控制变更\n")
        }

        return diagnosis.toString()
    }

    /**
     * 上报403错误详情
     */
    private fun report403Error(
        path: String,
        headers: okhttp3.Headers,
        diagnosis: String
    ) {
        val errorInfo =
            buildString {
                append("HTTP 403 Forbidden 错误详情:\n")
                append(diagnosis)
                append("\n完整请求头:\n")
                headers.toMultimap().forEach { (key, values) ->
                    // 隐藏敏感信息
                    val safeValues =
                        if (key.equals("Authorization", ignoreCase = true)) {
                            values.map { "***" }
                        } else {
                            values
                        }
                    append("  $key: $safeValues\n")
                }
            }

        // 使用专门的方法上报403错误，包含认证诊断信息
        val mockResponse = retrofit2.Response.error<Any>(403, okhttp3.ResponseBody.create(null, ""))
        val httpException = HttpException(mockResponse)
        ErrorReportHelper.post403Exception(
            httpException,
            "ForbiddenErrorInterceptor",
            "intercept",
            errorInfo,
            AuthenticationHelper.getAuthenticationDiagnosis(),
        )
    }

    /**
     * 尝试恢复策略
     */
    private fun tryRecovery(
        chain: Interceptor.Chain,
        request: okhttp3.Request,
        diagnosis: String
    ): Response {
        val userToken = UserConfig.getUserToken()
        val isLoggedIn = UserConfig.isUserLoggedIn()

        // 如果用户未登录或Token为空，无法恢复
        if (!isLoggedIn || userToken.isNullOrEmpty()) {
            return chain.proceed(request) // 返回原始403响应
        }

        // 如果是Authorization头缺失或格式错误，尝试重新构建请求
        val authHeader = request.headers["Authorization"]
        if (authHeader.isNullOrEmpty() || !authHeader.startsWith("Bearer ")) {
            val newRequest =
                request
                    .newBuilder()
                    .removeHeader("Authorization")
                    .header("Authorization", "Bearer $userToken")
                    .build()

            try {
                val newResponse = chain.proceed(newRequest)
                if (newResponse.code != 403) {
                    ErrorReportHelper.postException(
                        "403错误恢复成功：重新构建Authorization头\n$diagnosis",
                        "ForbiddenErrorInterceptor",
                    )
                    return newResponse
                }
            } catch (e: Exception) {
                ErrorReportHelper.postCatchedException(
                    e,
                    "ForbiddenErrorInterceptor",
                    "403错误恢复失败：重新构建请求时发生异常\n$diagnosis",
                )
            }
        }

        // 如果所有恢复策略都失败，返回原始403响应
        return chain.proceed(request)
    }
}
