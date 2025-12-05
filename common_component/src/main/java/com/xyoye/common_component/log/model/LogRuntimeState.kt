package com.xyoye.common_component.log.model

/**
 * 当前日志运行时状态，用于配置 UI 与调试开关。
 */
data class LogRuntimeState(
    val activePolicy: LogPolicy,
    val policySource: PolicySource = PolicySource.DEFAULT,
    val debugToggleState: DebugToggleState = DebugToggleState.OFF,
    val debugSessionEnabled: Boolean = debugToggleState == DebugToggleState.ON_CURRENT_SESSION,
    val lastPolicyUpdateTime: Long = System.currentTimeMillis(),
    val recentErrors: List<LogEvent> = emptyList()
) {
    init {
        require(lastPolicyUpdateTime > 0) { "lastPolicyUpdateTime must be positive" }
    }
}

enum class PolicySource {
    DEFAULT,
    USER_OVERRIDE,
    REMOTE
}

/**
 * 调试开关状态机（OFF → ON_CURRENT_SESSION → DISABLED_DUE_TO_ERROR）。
 */
enum class DebugToggleState {
    OFF,
    ON_CURRENT_SESSION,
    DISABLED_DUE_TO_ERROR
}
