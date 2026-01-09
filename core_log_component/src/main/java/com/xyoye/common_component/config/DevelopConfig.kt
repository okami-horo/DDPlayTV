package com.xyoye.common_component.config

/**
 * 日志相关的默认配置占位，后续策略接入前使用。
 */
object DevelopLogConfigDefaults {
    // 默认日志策略名称（与基础策略工厂保持一致）
    const val DEFAULT_LOG_POLICY_NAME = "default-release"

    // 是否允许写入调试日志（默认关闭以符合 FR-013/FR-014）
    const val DEFAULT_ENABLE_DEBUG_FILE = false

    // 单个调试日志文件大小上限占位（约 5MB）
    const val DEFAULT_LOG_FILE_SIZE_LIMIT_BYTES = 5 * 1024 * 1024L

    // 调试会话策略名称占位
    const val DEBUG_SESSION_POLICY_NAME = "debug-session"
}
