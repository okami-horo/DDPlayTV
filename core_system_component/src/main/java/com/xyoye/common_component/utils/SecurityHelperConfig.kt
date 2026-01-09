package com.xyoye.common_component.utils

import com.xyoye.core_system_component.BuildConfig

/**
 * 安全配置类
 * 支持从BuildConfig中读取编译时注入的密钥
 * 用于开源项目的密钥管理，通过GitHub Secrets安全注入
 *
 * Created by Claude Code on 2025-08-30.
 */
object SecurityHelperConfig {
    /**
     * Bugly App ID
     * 优先从BuildConfig读取，fallback到默认值
     */
    val BUGLY_APP_ID: String
        get() =
            when {
                // 从编译时注入的BuildConfig读取
                BuildConfig.BUGLY_APP_ID.isNotEmpty() &&
                    BuildConfig.BUGLY_APP_ID != "DEFAULT_BUGLY_ID" -> BuildConfig.BUGLY_APP_ID

                // 如果是调试模式，使用测试用的假ID（不会实际上报）
                BuildConfig.DEBUG -> "test_debug_id"

                // 生产模式但没有配置密钥时的默认处理
                else -> ""
            }

    /**
     * DanDan App ID
     * 优先从BuildConfig读取，fallback到默认值
     */
    val DANDAN_APP_ID: String
        get() =
            when {
                BuildConfig.DANDAN_APP_ID.isNotEmpty() &&
                    BuildConfig.DANDAN_APP_ID != "DEFAULT_DANDAN_ID" -> BuildConfig.DANDAN_APP_ID
                else -> ""
            }

    /**
     * 检查配置是否完整
     */
    fun isConfigured(): Boolean = BUGLY_APP_ID.isNotEmpty() && BUGLY_APP_ID != "test_debug_id"

    /**
     * 检查是否为调试模式的测试配置
     */
    fun isDebugMode(): Boolean = BuildConfig.DEBUG && BUGLY_APP_ID == "test_debug_id"

    /**
     * 获取Bugly状态信息
     */
    fun getBuglyStatusInfo(): BuglyStatusInfo =
        BuglyStatusInfo(
            isInitialized = BUGLY_APP_ID.isNotEmpty(),
            appId = BUGLY_APP_ID,
            isDebugMode = isDebugMode(),
            source =
                when {
                    BuildConfig.BUGLY_APP_ID.isNotEmpty() &&
                        BuildConfig.BUGLY_APP_ID != "DEFAULT_BUGLY_ID" -> "GitHub Secrets"
                    isDebugMode() -> "Debug Mode"
                    else -> "Not Configured"
                },
        )
}

/**
 * Bugly状态信息数据类
 */
data class BuglyStatusInfo(
    val isInitialized: Boolean,
    val appId: String,
    val isDebugMode: Boolean,
    val source: String
)
