package com.xyoye.common_component.utils

import com.xyoye.common_component.config.UserConfig
import com.xyoye.common_component.network.request.NetworkException

/**
 * 认证状态诊断和帮助工具类
 * 提供用户认证状态的检查和诊断功能
 *
 * Created by Claude Code on 2025-09-22.
 */
object AuthenticationHelper {
    /**
     * 检查认证状态是否有效
     * @return 认证状态信息和是否有效
     */
    fun checkAuthenticationStatus(): AuthenticationStatus {
        val userToken = UserConfig.getUserToken()
        val isLoggedIn = UserConfig.isUserLoggedIn()

        val status =
            AuthenticationStatus(
                isLoggedIn = isLoggedIn,
                hasToken = !userToken.isNullOrEmpty(),
                token = userToken?.take(8) + "..." ?: "", // 只显示前8位
                isValid = isLoggedIn && !userToken.isNullOrEmpty(),
                issues = mutableListOf(),
            )

        // 检查认证问题
        if (!isLoggedIn) {
            status.issues.add("用户未登录")
        }
        if (userToken.isNullOrEmpty()) {
            status.issues.add("用户Token为空")
        }
        if (isLoggedIn && userToken.isNullOrEmpty()) {
            status.issues.add("登录状态异常：已登录但Token为空")
        }
        if (userToken != null && userToken.length < 10) {
            status.issues.add("Token长度异常：可能不是有效的Token")
        }

        return status
    }

    /**
     * 获取认证状态诊断信息
     */
    fun getAuthenticationDiagnosis(): String {
        val status = checkAuthenticationStatus()

        return buildString {
            appendLine("=== 认证状态诊断 ===")
            appendLine("登录状态: ${if (status.isLoggedIn) "✅ 已登录" else "❌ 未登录"}")
            appendLine("Token状态: ${if (status.hasToken) "✅ 有Token" else "❌ 无Token"}")
            appendLine("Token预览: ${status.token}")
            appendLine("整体状态: ${if (status.isValid) "✅ 有效" else "❌ 无效"}")

            if (status.issues.isNotEmpty()) {
                appendLine()
                appendLine("发现的问题:")
                status.issues.forEach { issue ->
                    appendLine("  ❌ $issue")
                }
            }

            appendLine()
            appendLine("=== 建议 ===")
            if (!status.isValid) {
                if (!status.isLoggedIn) {
                    appendLine("- 请用户登录账号")
                }
                if (!status.hasToken) {
                    appendLine("- 重新登录以获取有效Token")
                }
                appendLine("- 检查网络连接")
                appendLine("- 确认服务器状态正常")
            } else {
                appendLine("- 认证状态正常")
                appendLine("- 如仍遇到403错误，可能是服务器端权限变更")
            }
        }
    }

    /**
     * 创建认证相关的异常
     */
    fun createAuthenticationException(): NetworkException {
        val diagnosis = getAuthenticationDiagnosis()
        return NetworkException(
            code = 403,
            msg = "认证失败",
            cause = IllegalStateException(diagnosis),
        )
    }

    /**
     * 认证状态数据类
     */
    data class AuthenticationStatus(
        val isLoggedIn: Boolean,
        val hasToken: Boolean,
        val token: String,
        val isValid: Boolean,
        val issues: MutableList<String>
    )
}
